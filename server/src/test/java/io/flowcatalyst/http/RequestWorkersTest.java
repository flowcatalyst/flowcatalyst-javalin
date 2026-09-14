package io.flowcatalyst.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.http.vertx.VertxListener;
import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/// Request-level admission (`docs/spec/admission.md` §9, §11.7 part B): FIFO, bounded per
/// group, groups isolated, `NO_DB` unbounded. There is no more "MAIN" fallback bucket —
/// every group these unit tests exercise is a real [Group].
class RequestWorkersTest {

    @Test
    void oneWorkerRunsQueuedTasksInSubmissionOrder() throws Exception {
        try (var w = RequestWorkers.of(Map.of(Group.API_WRITE, 1))) {
            var gate = new CountDownLatch(1);
            var order = new CopyOnWriteArrayList<Integer>();
            var done = new CountDownLatch(4);
            w.submit(Group.API_WRITE, () -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add(0);
                done.countDown();
            });
            for (int i = 1; i <= 3; i++) {
                int n = i;
                w.submit(Group.API_WRITE, () -> {
                    order.add(n);
                    done.countDown();
                });
            }
            Thread.sleep(100);
            assertThat(w.queued(Group.API_WRITE)).isEqualTo(3);
            gate.countDown();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(0, 1, 2, 3);
        }
    }

    @Test
    void aPoolRunsAtMostItsSizeConcurrently() throws Exception {
        try (var w = RequestWorkers.of(Map.of(Group.API_WRITE, 2))) {
            var release = new CountDownLatch(1);
            var started = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                w.submit(Group.API_WRITE, () -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(started.await(300, TimeUnit.MILLISECONDS)).as("the third must wait for a worker").isFalse();
            assertThat(w.busy(Group.API_WRITE)).isEqualTo(2);
            assertThat(w.queued(Group.API_WRITE)).isEqualTo(1);
            release.countDown();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aBlockedDispatchPoolDoesNotBlockAnotherGroupAndNoDbIsUnbounded() throws Exception {
        try (var w = RequestWorkers.of(Map.of(Group.DISPATCH, 1, Group.API_WRITE, 1))) {
            var release = new CountDownLatch(1);
            Runnable block = () -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            w.submit(Group.DISPATCH, block);
            w.submit(Group.API_WRITE, block);
            Thread.sleep(100);
            assertThat(w.busy(Group.DISPATCH)).isEqualTo(1);
            assertThat(w.busy(Group.API_WRITE)).isEqualTo(1);
            var ran = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) w.submit(Group.NO_DB, ran::countDown);
            assertThat(ran.await(1, TimeUnit.SECONDS)).as("NO_DB never waits for a worker").isTrue();
            assertThat(w.queued(Group.API_WRITE)).isZero();
            release.countDown();
        }
    }

    @Test
    void submitForAGroupWithNoConfiguredPoolThrows() {
        try (var w = RequestWorkers.of(Map.of(Group.API_WRITE, 1))) {
            assertThatThrownBy(() -> w.submit(Group.API_READ, () -> { }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void aFullQueueIsRefusedWithoutRunningTheTaskAndCountsAsRejected() throws Exception {
        // bound = 8 * size; size=1 -> bound 8. One busy worker + 8 queued fills it exactly.
        try (var w = RequestWorkers.of(Map.of(Group.API_WRITE, 1))) {
            var release = new CountDownLatch(1);
            var ran = new java.util.concurrent.atomic.AtomicInteger();
            assertThat(w.submit(Group.API_WRITE, () -> {
                ran.incrementAndGet();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })).isTrue();
            for (int i = 0; i < 8; i++) {
                assertThat(w.submit(Group.API_WRITE, ran::incrementAndGet)).isTrue();
            }
            assertThat(w.rejected(Group.API_WRITE)).isZero();
            // the queue is now exactly at its bound (8) plus one busy worker.
            var refused = w.submit(Group.API_WRITE, ran::incrementAndGet);
            assertThat(refused).as("the queue was already at its bound").isFalse();
            assertThat(w.rejected(Group.API_WRITE)).isEqualTo(1);
            release.countDown();
            // only the accepted tasks ever ran (1 busy + 8 queued = 9, never the refused one).
            Thread.sleep(200);
            assertThat(ran.get()).isEqualTo(9);
        }
    }

    @Test
    void theListenerQueuesTheThirdRequestWhenTwoWorkersAreBusyAndAnswersItAfterwards() throws Exception {
        var inHandler = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var workers = RequestWorkers.of(Map.of(Group.API_WRITE, 2));
        try (var l = VertxListener.start(VertxListener.Options.local(0, workers), routes -> {
            routes.exception(Exception.class, (e, ctx) -> ctx.status(500).result("err"));
            routes.in(Group.API_WRITE).get("/w", ctx -> {
                inHandler.countDown();
                release.await();
                ctx.result("ok");
            });
            routes.in(Group.NO_DB).get("/light", ctx -> ctx.result("light"));
        })) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/w")).timeout(Duration.ofSeconds(10)).build();
            List<CompletableFuture<HttpResponse<String>>> rs = List.of(
                    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()),
                    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()),
                    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
            assertThat(inHandler.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            assertThat(workers.busy(Group.API_WRITE)).isEqualTo(2);
            assertThat(workers.queued(Group.API_WRITE)).as("the third request is queued, not in a handler").isEqualTo(1);
            var light = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/light")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(light.statusCode()).as("a NO_DB route answers while every worker is busy").isEqualTo(200);
            release.countDown();
            for (var r : rs) assertThat(r.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(workers.queued(Group.API_WRITE)).isZero();
        }
    }

    /// `docs/spec/admission.md` §11.7 "Workers": API_WRITE = api pool size, API_READ = 2x,
    /// BFF = 2x the bff pool size, DISPATCH = dispatch pool size, LOGIN/OIDC = processor
    /// count. Mutant: swap API_READ and API_WRITE's multipliers (or BFF's) — this fails.
    @Test
    void derivedSizesFollowTheSpecMultipliers() {
        try (Pools pools = Pools.open(TestPg.instance().getJdbcUrl("postgres", "postgres"),
                new io.flowcatalyst.server.EnvReader(Map.of()))) {
            try (var w = RequestWorkers.derived(pools)) {
                int cores = Runtime.getRuntime().availableProcessors();
                assertThat(w.size(Group.API_WRITE)).isEqualTo(pools.api().ordinaryPermits());
                assertThat(w.size(Group.API_READ)).isEqualTo(2 * pools.api().ordinaryPermits());
                assertThat(w.size(Group.BFF)).isEqualTo(2 * pools.bff().ordinaryPermits());
                assertThat(w.size(Group.DISPATCH)).isEqualTo(pools.dispatch().ordinaryPermits());
                assertThat(w.size(Group.LOGIN)).isEqualTo(cores);
                assertThat(w.size(Group.OIDC)).isEqualTo(cores);
            }
        }
    }

    /// `docs/spec/admission.md` §11.7 "Workers": the four series, labelled `group`.
    @Test
    void theCollectorExposesAllFourSeriesLabelledByGroup() throws Exception {
        try (var w = RequestWorkers.of(Map.of(Group.API_WRITE, 1))) {
            var done = new CountDownLatch(1);
            w.submit(Group.API_WRITE, done::countDown);
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50); // let the worker record the queue-wait observation
            w.submit(Group.API_WRITE, () -> { });
            var registry = new io.prometheus.metrics.model.registry.PrometheusRegistry();
            registry.register(w.collector());
            MetricSnapshots snapshot = registry.scrape();
            Set<String> names = snapshot.stream().map(s -> s.getMetadata().getName()).collect(Collectors.toSet());
            // The library's CounterSnapshot strips a declared "_total" suffix from its own
            // canonical name (it is re-added at text-exposition time) — "fc_request_rejected"
            // here is the same series as fc_request_rejected_total on the wire.
            assertThat(names).containsExactlyInAnyOrder("fc_request_workers_busy", "fc_request_queue_depth",
                    "fc_request_rejected", "fc_request_queue_wait_seconds");
            var histo = (HistogramSnapshot) snapshot.stream()
                    .filter(s -> s.getMetadata().getName().equals("fc_request_queue_wait_seconds")).findFirst().orElseThrow();
            var dp = histo.getDataPoints().get(0);
            assertThat(dp.getLabels().get("group")).isEqualTo("API_WRITE");
            assertThat(dp.getCount()).as("one observation recorded").isGreaterThanOrEqualTo(1);
        }
    }
}

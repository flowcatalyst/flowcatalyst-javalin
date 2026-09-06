package io.flowcatalyst.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.http.vertx.VertxListener;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// Request-level admission: FIFO, bounded per pool, groups isolated, NO_DB unbounded.
class RequestWorkersTest {

    @Test
    void oneWorkerRunsQueuedTasksInSubmissionOrder() throws Exception {
        try (var w = RequestWorkers.of(1, Map.of())) {
            var gate = new CountDownLatch(1);
            var order = new CopyOnWriteArrayList<Integer>();
            var done = new CountDownLatch(4);
            w.submit(null, () -> {
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
                w.submit(null, () -> {
                    order.add(n);
                    done.countDown();
                });
            }
            Thread.sleep(100);
            assertThat(w.queued(null)).isEqualTo(3);
            gate.countDown();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(0, 1, 2, 3);
        }
    }

    @Test
    void aPoolRunsAtMostItsSizeConcurrently() throws Exception {
        try (var w = RequestWorkers.of(2, Map.of())) {
            var release = new CountDownLatch(1);
            var started = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                w.submit(null, () -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(started.await(300, TimeUnit.MILLISECONDS)).as("the third must wait for a worker").isFalse();
            assertThat(w.busy(null)).isEqualTo(2);
            assertThat(w.queued(null)).isEqualTo(1);
            release.countDown();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aBlockedDispatchPoolDoesNotBlockTheMainPoolAndNoDbIsUnbounded() throws Exception {
        try (var w = RequestWorkers.of(1, Map.of(Group.DISPATCH, 1))) {
            var release = new CountDownLatch(1);
            Runnable block = () -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            w.submit(Group.DISPATCH, block);
            w.submit(null, block);
            Thread.sleep(100);
            assertThat(w.busy(Group.DISPATCH)).isEqualTo(1);
            assertThat(w.busy(null)).isEqualTo(1);
            var ran = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) w.submit(Group.NO_DB, ran::countDown);
            assertThat(ran.await(1, TimeUnit.SECONDS)).as("NO_DB never waits for a worker").isTrue();
            assertThat(w.queued(null)).isZero();
            release.countDown();
        }
    }

    @Test
    void theListenerQueuesTheThirdRequestWhenTwoWorkersAreBusyAndAnswersItAfterwards() throws Exception {
        var inHandler = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var workers = RequestWorkers.of(2, Map.of());
        try (var l = VertxListener.start(VertxListener.Options.local(0, Budgets.none(), workers), routes -> {
            routes.exception(Exception.class, (e, ctx) -> ctx.status(500).result("err"));
            routes.get("/w", ctx -> {
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
            assertThat(workers.busy(null)).isEqualTo(2);
            assertThat(workers.queued(null)).as("the third request is queued, not in a handler").isEqualTo(1);
            var light = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.port() + "/light")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(light.statusCode()).as("a NO_DB route answers while every worker is busy").isEqualTo(200);
            release.countDown();
            for (var r : rs) assertThat(r.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(workers.queued(null)).isZero();
        }
    }
}

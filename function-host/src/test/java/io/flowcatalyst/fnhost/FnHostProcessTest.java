package io.flowcatalyst.fnhost;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.reconcile.ControlPlane;
import io.flowcatalyst.fnhost.reconcile.ControlPlaneException;
import io.flowcatalyst.fnhost.reconcile.FakeControlPlane;
import io.flowcatalyst.fnhost.reconcile.HostEnv;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [FnHost] end to end (`docs/spec/function-host-process.md` §2, test P1):
/// `/ready`'s full state machine through a real [FnObservability] listener,
/// fed by a [FakeControlPlane]-backed [Reconciler]. `FnObservabilityIndependenceTest`
/// (package `.http`) is P7 — the same listener staying responsive while the
/// function listener's event loop is blocked.
class FnHostProcessTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HostEnv env(Path cacheDir) {
        return new HostEnv(new DnsLabel("pool"), "http://127.0.0.1:1", "client-1", "secret-1", "host-1",
                new Signatures.Off(), 50, cacheDir, 0, 512, 3, 0, false);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Reconciler reconcilerOn(FakeControlPlane controlPlane, FunctionRegistry registry, Path cacheDir) {
        return new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(cacheDir), new Signatures.Off(), new JvmFunctionLoader(), registry);
    }

    private static int awaitObservabilityPort(FnHost host) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            int p = host.metricsPort();
            if (p > 0) {
                return p;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("observability port never bound");
    }

    // ── P1: STARTING -> READY -> stays READY through a later outage -> DRAINING; /health throughout ──

    @Test
    void p1_readinessTransitionsStartingToReadyThroughOutageToDraining(@TempDir Path dir) throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        CountDownLatch releaseFirstFetch = new CountDownLatch(1);
        controlPlane.desiredStateReturns((pool, etag) -> {
            releaseFirstFetch.await();
            return new ControlPlane.Fetched.NotModified();
        });
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler reconciler = reconcilerOn(controlPlane, registry, dir.resolve("cache"));
        FnHost host = new FnHost(env(dir.resolve("cache2")), reconciler, registry);

        // Deliberately NOT try-with-resources for the executor: if an assertion below
        // throws before the latch is released, an auto-close would wait forever for
        // host.start()'s still-parked virtual thread. The latch release is unconditional
        // in the outer finally, ahead of everything else, so that virtual thread — and so
        // host.close() below it — can always actually finish.
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CompletableFuture<Void> starting = CompletableFuture.runAsync(host::start, executor);

            // The observability listener binds BEFORE the first reconcile attempt (FnHost's
            // own ordering) — /ready must answer STARTING for real while the fetch is parked,
            // not "always already past the first attempt by the time anything could ask".
            int port = awaitObservabilityPort(host);
            assertThat(get(port, "/health").statusCode())
                    .as("mutant: unready on any failure — health is unconditional").isEqualTo(200);
            var starting503 = get(port, "/ready");
            assertThat(starting503.statusCode())
                    .as("mutant: ready on first attempt rather than first success").isEqualTo(503);
            assertThat(starting503.body()).contains("STARTING");

            releaseFirstFetch.countDown();
            starting.get(30, TimeUnit.SECONDS);

            var readyAfterSuccess = get(port, "/ready");
            assertThat(readyAfterSuccess.statusCode()).isEqualTo(200);

            // A LATER outage: stays ready (D2 R6) — only PLATFORM_UNREACHABLE before the
            // first success ever counts as unready.
            controlPlane.desiredStateReturns((pool, etag) -> {
                throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "simulated outage");
            });
            reconciler.reconcileOnce(Instant.now());
            var readyThroughOutage = get(port, "/ready");
            assertThat(readyThroughOutage.statusCode())
                    .as("mutant: unready on any failure").isEqualTo(200);

            // DRAINING wins over everything else, one-way.
            reconciler.drain();
            var draining = get(port, "/ready");
            assertThat(draining.statusCode()).isEqualTo(503);
            assertThat(draining.body()).contains("DRAINING");
            assertThat(get(port, "/health").statusCode())
                    .as("mutant: unready on any failure — health stays up even draining").isEqualTo(200);
        } finally {
            releaseFirstFetch.countDown();
            host.close();
            executor.close();
        }
    }

    // ── P1: a FAILED first reconcile is PLATFORM_UNREACHABLE, not STARTING or READY ──

    @Test
    void p1_platformUnreachableAfterAFailedFirstReconcile(@TempDir Path dir) throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        controlPlane.desiredStateReturns((pool, etag) -> {
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "simulated outage");
        });
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler reconciler = reconcilerOn(controlPlane, registry, dir.resolve("cache"));
        FnHost host = new FnHost(env(dir.resolve("cache2")), reconciler, registry);
        try {
            host.start();
            var ready = get(host.metricsPort(), "/ready");
            assertThat(ready.statusCode()).isEqualTo(503);
            assertThat(ready.body())
                    .as("mutant: ready on first attempt rather than first success").contains("PLATFORM_UNREACHABLE");

            // A second failed attempt: still PLATFORM_UNREACHABLE, never READY.
            reconciler.reconcileOnce(Instant.now());
            var stillUnreachable = get(host.metricsPort(), "/ready");
            assertThat(stillUnreachable.statusCode()).isEqualTo(503);
            assertThat(stillUnreachable.body()).contains("PLATFORM_UNREACHABLE");
        } finally {
            host.close();
        }
    }

    // ── /metrics exposes the fixed-shape series even with nothing loaded ──

    @Test
    void metricsScrapeIncludesTheHostsOwnSeries(@TempDir Path dir) throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler reconciler = reconcilerOn(controlPlane, registry, dir.resolve("cache"));
        FnHost host = new FnHost(env(dir.resolve("cache2")), reconciler, registry);
        try {
            host.start();
            var scrape = get(host.metricsPort(), "/metrics");
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("fc_fn_loaded", "fc_fn_warm", "fc_fn_reconcile_total",
                    "fc_fn_last_reconcile_success_timestamp_seconds", "jvm_memory");
        } finally {
            host.close();
        }
    }
}

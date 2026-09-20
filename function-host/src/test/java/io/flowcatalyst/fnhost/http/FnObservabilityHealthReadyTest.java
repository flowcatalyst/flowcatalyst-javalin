package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.reconcile.ControlPlane;
import io.flowcatalyst.fnhost.reconcile.FakeControlPlane;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/function-host-process.md` §3 item 3: `/health`/`/ready` wired
/// to live listener-bound / reconcile-loop-alive / startup-complete state,
/// through the real HTTP handler — [io.flowcatalyst.fnhost.reconcile.ReconcilerReadinessTest]
/// (package `.reconcile`) pins the pure precedence logic; this pins that
/// [FnObservability] actually asks the right suppliers and maps each state
/// to the right status code and body.
class FnObservabilityHealthReadyTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void healthStaysUpUntilStartupCompletesThenTellsTheTruthAboutListenerAndLoop(@TempDir Path dir) throws Exception {
        Reconciler reconciler = reconciler(dir);
        AtomicBoolean listenerBound = new AtomicBoolean(false);
        AtomicBoolean loopAlive = new AtomicBoolean(false);
        AtomicBoolean startupComplete = new AtomicBoolean(false);

        try (var observability = FnObservability.start(reconciler, new PrometheusRegistry(),
                FnObservability.Options.of(0), listenerBound::get, loopAlive::get, startupComplete::get)) {

            // Before start-up completes: 200 no matter how unhealthy listener/loop look —
            // a slow first load must never fail a liveness probe.
            assertThat(get(observability.port(), "/health").statusCode())
                    .as("mutant: check listener/loop even before startup completes").isEqualTo(200);

            startupComplete.set(true);

            var listenerDown = get(observability.port(), "/health");
            assertThat(listenerDown.statusCode())
                    .as("mutant: /health ignores listener state once startup has completed").isEqualTo(503);
            assertThat(listenerDown.body()).contains("LISTENER_DOWN");

            listenerBound.set(true);
            var reconcilerDown = get(observability.port(), "/health");
            assertThat(reconcilerDown.statusCode())
                    .as("mutant: /health ignores loop-alive state once startup has completed").isEqualTo(503);
            assertThat(reconcilerDown.body()).contains("RECONCILER_DOWN");

            loopAlive.set(true);
            var healthy = get(observability.port(), "/health");
            assertThat(healthy.statusCode()).isEqualTo(200);
            assertThat(healthy.body()).contains("UP");
        }
    }

    @Test
    void readyReflectsListenerAndLoopOnceTheReconcilerHasSucceeded(@TempDir Path dir) throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        controlPlane.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.NotModified());
        Reconciler reconciler = new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(),
                new FunctionRegistry(10));
        reconciler.reconcileOnce(Instant.now()); // a real success — everReconciledSuccessfully = true

        AtomicBoolean listenerBound = new AtomicBoolean(true);
        AtomicBoolean loopAlive = new AtomicBoolean(true);

        try (var observability = FnObservability.start(reconciler, new PrometheusRegistry(),
                FnObservability.Options.of(0), listenerBound::get, loopAlive::get, () -> true)) {

            assertThat(get(observability.port(), "/ready").statusCode())
                    .as("everything healthy: READY").isEqualTo(200);

            listenerBound.set(false);
            var listenerDown = get(observability.port(), "/ready");
            assertThat(listenerDown.statusCode())
                    .as("mutant: /ready ignores whether the function listener is bound").isEqualTo(503);
            assertThat(listenerDown.body()).contains("LISTENER_DOWN");

            listenerBound.set(true);
            loopAlive.set(false);
            var reconcilerDown = get(observability.port(), "/ready");
            assertThat(reconcilerDown.statusCode())
                    .as("mutant: /ready ignores whether the reconcile loop is alive").isEqualTo(503);
            assertThat(reconcilerDown.body()).contains("RECONCILER_DOWN");
        }
    }

    private static Reconciler reconciler(Path dir) {
        FakeControlPlane controlPlane = new FakeControlPlane();
        return new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(),
                new FunctionRegistry(10));
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}

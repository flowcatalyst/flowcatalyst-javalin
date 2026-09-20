package io.flowcatalyst.fnhost;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.reconcile.ControlPlane;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// `docs/spec/function-host-process.md` §3 item 2: an `Error` escaping the
/// very FIRST reconcile must never leave [FnHost#start] half-finished — the
/// ORIGINAL defect this fixes was exactly this: the function listener never
/// bound, while the observability listener (bound earlier in `start()`)
/// kept answering `/ready` 200 forever. `ReconcileLoopTest` pins the SAME
/// resilience for a LATER reconcile, inside the loop; this pins it for the
/// FIRST one, which runs synchronously inside `start()` itself, outside the
/// loop's own catch entirely.
class FnHostStartupResilienceTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HostEnv env(Path cacheDir) {
        return new HostEnv(new DnsLabel("pool"), "http://127.0.0.1:1", "client-1", "secret-1", "host-1",
                new Signatures.Off(), 50, cacheDir, 0, 512, 3, 0, false, 16);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anErrorFromTheFirstReconcileStillBindsTheFunctionListenerAndTheHostRecoversLater(@TempDir Path dir)
            throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        AtomicInteger calls = new AtomicInteger();
        controlPlane.desiredStateReturns((pool, etag) -> {
            if (calls.incrementAndGet() == 1) {
                // From OUTSIDE JvmFunctionLoader/Reconciler's own per-function metaspace guard
                // entirely — a failure in fetching/parsing the desired-state document itself.
                throw new OutOfMemoryError("Metaspace");
            }
            return new ControlPlane.Fetched.NotModified();
        });
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler reconciler = new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        FnHost host = new FnHost(env(dir.resolve("cache2")), reconciler, registry);
        try {
            assertThatCode(host::start)
                    .as("mutant: let the Error from the first reconcile escape start() uncaught")
                    .doesNotThrowAnyException();

            assertThat(host.port())
                    .as("mutant: die before binding the FUNCTION listener — the ORIGINAL defect").isPositive();
            assertThat(host.metricsPort()).isPositive();

            // Start-up has completed (start() returned): /health now reflects real listener/loop
            // state rather than the unconditional "still starting" 200 — both are healthy here.
            assertThat(get(host.metricsPort(), "/health").statusCode()).isEqualTo(200);

            // A later, successful cycle recovers readiness fully — the host is not wedged.
            reconciler.reconcileOnce(Instant.now());
            assertThat(get(host.metricsPort(), "/ready").statusCode())
                    .as("mutant: a transient startup Error leaves the host permanently unready").isEqualTo(200);
        } finally {
            host.close();
        }
    }

    /// The WIRING, not the pieces: `FnObservabilityHealthReadyTest` drives fake
    /// suppliers and `ReconcileLoopTest` a loop of its own, so a `FnHost` that
    /// handed `/health` a constant `true` passed both. Here the host's OWN loop
    /// dies of a non-metaspace `Error` and the host's OWN `/health` must say so —
    /// that 503 is what makes ECS replace the task.
    @Test
    void whenTheHostsOwnReconcileLoopDiesHealthAndReadyTurn503(@TempDir Path dir) throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        // Switched on by the test: the loop runs a cycle of its own as soon as it starts, so a
        // "second call throws" script would kill it before the healthy state could be observed.
        java.util.concurrent.atomic.AtomicBoolean die = new java.util.concurrent.atomic.AtomicBoolean();
        controlPlane.desiredStateReturns((pool, etag) -> {
            if (die.get()) {
                throw new StackOverflowError("not a metaspace error: the loop must end");
            }
            return new ControlPlane.Fetched.NotModified();
        });
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler reconciler = new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        FnHost host = new FnHost(env(dir.resolve("cache2")), reconciler, registry);
        try {
            host.start();
            assertThat(get(host.metricsPort(), "/health").statusCode()).as("healthy while the loop lives").isEqualTo(200);

            die.set(true);
            host.triggerReconcile();

            HttpResponse<String> health = null;
            for (int i = 0; i < 100; i++) {
                health = get(host.metricsPort(), "/health");
                if (health.statusCode() == 503) {
                    break;
                }
                Thread.sleep(100);
            }
            assertThat(health.statusCode()).as("mutant: FnHost wires /health to a constant true").isEqualTo(503);
            assertThat(health.body()).contains("RECONCILER_DOWN");
            assertThat(get(host.metricsPort(), "/ready").statusCode()).isEqualTo(503);
        } finally {
            host.close();
        }
    }
}

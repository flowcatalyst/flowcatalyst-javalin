package io.flowcatalyst.fnhost;

import io.flowcatalyst.fnhost.http.FnHttpServer;
import io.flowcatalyst.fnhost.http.FnObservability;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.metrics.FnMetrics;
import io.flowcatalyst.fnhost.reconcile.HostEnv;
import io.flowcatalyst.fnhost.reconcile.HttpControlPlane;
import io.flowcatalyst.fnhost.reconcile.ReconcileLoop;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.fnhost.reconcile.TokenSource;
import io.flowcatalyst.platform.function.artifact.ArtifactStores;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.OciArtifactStore;
import io.flowcatalyst.platform.function.artifact.RegistryCredentials;
import io.flowcatalyst.server.JvmMetricsRegistration;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/// Assembles and runs the host process (spec `function-host-listener.md`
/// §5, `function-host-process.md` §2): `HostEnv → ArtifactStores →
/// Reconciler + ReconcileLoop → FnHttpServer`, plus D5's own
/// [FnMetrics]/[FnObservability] — a fresh, per-instance [PrometheusRegistry]
/// (never the shared [PrometheusRegistry#defaultRegistry] the server uses),
/// so two [FnHost]s never collide registering the same series name in one
/// JVM, as tests routinely construct several.
public final class FnHost implements AutoCloseable {

    private final HostEnv env;
    private final FunctionRegistry registry;
    private final Reconciler reconciler;
    private final ReconcileLoop loop;
    private final PrometheusRegistry prometheusRegistry;
    private final FnMetrics metrics;
    private volatile FnHttpServer server;
    private volatile FnObservability observability;

    /// Counted down once, by [#close] — [#awaitStop] is [FnHostMain]'s own
    /// seam for blocking the main thread until a shutdown-hook-driven
    /// [#close] completes, same shape as `io.flowcatalyst.server.Server.Running#awaitStop`.
    private final CountDownLatch stopped = new CountDownLatch(1);

    public FnHost(HostEnv env) {
        this.env = Objects.requireNonNull(env, "env");
        this.registry = new FunctionRegistry(env.maxLoaded());
        ArtifactStores stores = new ArtifactStores(
                new FileArtifactStore(env.cacheDir()),
                new OciArtifactStore(env.cacheDir(), RegistryCredentials.none()));
        HttpClient http = HttpClient.newHttpClient();
        TokenSource tokenSource = new TokenSource(http, env.platformUrl(), env.clientId(), env.clientSecret());
        HttpControlPlane controlPlane = new HttpControlPlane(env.platformUrl(), tokenSource);
        this.reconciler = new Reconciler(env.pool(), env.hostId(), controlPlane, stores, env.signatures(),
                new JvmFunctionLoader(), registry);
        this.loop = new ReconcileLoop(reconciler);
        this.prometheusRegistry = new PrometheusRegistry();
        this.metrics = wireMetrics(prometheusRegistry, registry, reconciler);
    }

    /// Test/composition seam: an already-built [Reconciler] (a fake control
    /// plane, an in-memory artifact store, …) instead of the real HTTP
    /// assembly above. D5's metrics/observability wiring is identical either
    /// way.
    public FnHost(HostEnv env, Reconciler reconciler, FunctionRegistry registry) {
        this.env = Objects.requireNonNull(env, "env");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.loop = new ReconcileLoop(reconciler);
        this.prometheusRegistry = new PrometheusRegistry();
        this.metrics = wireMetrics(prometheusRegistry, registry, reconciler);
    }

    /// [FnMetrics] registers its own series on `prometheusRegistry`, the JVM
    /// series go on the same registry ("plus `JvmMetricsRegistration` as the
    /// server does", spec §2), and the reconciler is told about both
    /// observer roles ([Reconciler#setObserver]) and the cardinality sweep
    /// ([Reconciler#addPostReconcileListener]) — all BEFORE [#start] ever
    /// calls [Reconciler#reconcileOnce], so nothing is missed on cycle one.
    private static FnMetrics wireMetrics(PrometheusRegistry prometheusRegistry, FunctionRegistry registry,
                                          Reconciler reconciler) {
        JvmMetricsRegistration.register(prometheusRegistry);
        FnMetrics metrics = new FnMetrics(prometheusRegistry, registry);
        reconciler.setObserver(metrics);
        reconciler.addPostReconcileListener(() -> metrics.sweepDeadAddresses(reconciler.desiredAddresses()));
        return metrics;
    }

    /// Spec `function-host-listener.md` §5: the FUNCTION listener binds only
    /// after the first reconcile attempt (success or failure) — a host must
    /// never answer 404 for a function it simply has not heard of yet.
    /// `function-host-process.md` §2 P1's observability listener is the
    /// opposite: it binds FIRST, before that attempt even runs, precisely so
    /// `/ready` can report `STARTING` for real (P1's own first row) instead
    /// of a first reconcile that has, by construction, always already
    /// happened by the time anything could ask it.
    public void start() {
        observability = FnObservability.start(reconciler, prometheusRegistry, FnObservability.Options.of(env.metricsPort()));
        reconciler.reconcileOnce(Instant.now());
        loop.start();
        server = FnHttpServer.start(reconciler,
                FnHttpServer.Options.of(env.port(), env.maxConcurrency(), env.platformUrl(), metrics));
    }

    public int port() {
        FnHttpServer s = server;
        return s == null ? -1 : s.port();
    }

    /// The observability listener's bound port (`/health`, `/ready`,
    /// `/metrics`) — `-1` before [#start].
    public int metricsPort() {
        FnObservability o = observability;
        return o == null ? -1 : o.port();
    }

    public Reconciler reconciler() {
        return reconciler;
    }

    /// Spec §5: `drain()` (heartbeat `DRAINING`), stop accepting, wait for
    /// in-flight up to `FC_DRAIN_TIMEOUT_SECONDS` (default 60), close the
    /// loop, close every loaded function. The observability listener is
    /// closed last — `/health`/`/ready` should keep answering (`DRAINING`)
    /// for as long as anything else here is still winding down.
    @Override
    public void close() {
        reconciler.drain();
        FnHttpServer s = server;
        if (s != null) {
            s.drain();
            s.close(Duration.ofSeconds(env.drainTimeoutSeconds()));
        }
        loop.close();
        for (FunctionRegistry.Snapshot snapshot : registry.snapshot()) {
            LoadedFunction removed = registry.remove(snapshot.address());
            if (removed != null) {
                removed.close();
            }
        }
        FnObservability o = observability;
        if (o != null) {
            o.close();
        }
        stopped.countDown();
    }

    /// Blocks until [#close] completes — [FnHostMain]'s seam for keeping the
    /// main thread alive until a shutdown-hook-driven [#close] runs.
    public void awaitStop() throws InterruptedException {
        stopped.await();
    }
}

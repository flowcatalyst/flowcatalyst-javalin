package io.flowcatalyst.fnhost;

import io.flowcatalyst.fnhost.context.ContextFactory;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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

    private static final Logger LOG = LoggerFactory.getLogger(FnHost.class);

    /// `function-host-process.md` §3 item 2: a precomputed fallback line —
    /// built once, no per-failure string concatenation — for [GuardedLog]
    /// if the ordinary log call for a `Throwable` escaping the first
    /// reconcile itself throws.
    private static final byte[] START_RECONCILE_FAILURE_FALLBACK =
            ("ERROR the first reconcile threw during startup; continuing to bind the function listener anyway"
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);

    private final HostEnv env;
    private final FunctionRegistry registry;
    private final Reconciler reconciler;
    private final ReconcileLoop loop;
    private final PrometheusRegistry prometheusRegistry;
    private final FnMetrics metrics;
    private volatile FnHttpServer server;
    private volatile FnObservability observability;

    /// `function-host-process.md` §3 item 3: true once [#start] has fully
    /// returned — `/health` stays 200 unconditionally before this (a slow
    /// first load must not kill a liveness probe) and reflects real
    /// listener/loop health after.
    private volatile boolean startupComplete;

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
        ContextFactory contextFactory = ContextFactory.production(env.maxDbPools(), controlPlane, env.hostId());
        this.reconciler = new Reconciler(env.pool(), env.hostId(), controlPlane, stores, env.signatures(),
                new JvmFunctionLoader(), registry, contextFactory);
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
        observability = FnObservability.start(reconciler, prometheusRegistry,
                FnObservability.Options.of(env.metricsPort()), () -> server != null, loop::isAlive,
                () -> startupComplete);
        try {
            reconciler.reconcileOnce(Instant.now());
        } catch (Throwable t) {
            // `function-host-process.md` §3 item 2: an Error escaping the very first
            // reconcile must never leave the host half-started. This is the ORIGINAL
            // defect this fixes — before this guard, an OutOfMemoryError here killed
            // start() before the function listener ever bound, while the observability
            // listener (already bound above) kept answering /ready 200 forever, with
            // port 8080 never open. A host that serves whatever it managed to load
            // beats a zombie that never binds at all — so start-up continues regardless.
            GuardedLog.logThrowableSafely(LOG,
                    "first reconcile failed during startup; continuing to bind the function listener regardless",
                    t, START_RECONCILE_FAILURE_FALLBACK);
        }
        loop.start();
        // Spec `function-public-routes.md` §3: the public listener is bound here too, from
        // the SAME HostEnv — a mutant that never wires env.publicPort()/env.trustedProxies()
        // through would leave `off`/8081 dead regardless of what an operator configured.
        server = FnHttpServer.start(reconciler,
                FnHttpServer.Options.of(env.port(), env.maxConcurrency(), env.platformUrl(), metrics,
                        env.publicPort(), env.trustedProxies()));
        startupComplete = true;
    }

    public int port() {
        FnHttpServer s = server;
        return s == null ? -1 : s.port();
    }

    /// The PUBLIC listener's bound port (spec `function-public-routes.md`
    /// §3), or [FnHttpServer#PUBLIC_PORT_DISABLED] before [#start] or when
    /// `FC_FN_PUBLIC_PORT=off`.
    public int publicPort() {
        FnHttpServer s = server;
        return s == null ? FnHttpServer.PUBLIC_PORT_DISABLED : s.publicPort();
    }

    /// The observability listener's bound port (`/health`, `/ready`,
    /// `/metrics`) — `-1` before [#start].
    public int metricsPort() {
        FnObservability o = observability;
        return o == null ? -1 : o.port();
    }

    /// Asks the loop for a reconcile now (coalesced — see [ReconcileLoop#trigger]).
    /// What an operator action or a platform notification would call; tests use
    /// it to drive the host's OWN loop rather than a second one beside it.
    public void triggerReconcile() {
        loop.trigger();
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

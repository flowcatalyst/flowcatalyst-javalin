package io.flowcatalyst.fnhost;

import io.flowcatalyst.fnhost.http.FnHttpServer;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.reconcile.HostEnv;
import io.flowcatalyst.fnhost.reconcile.HttpControlPlane;
import io.flowcatalyst.fnhost.reconcile.ReconcileLoop;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.fnhost.reconcile.TokenSource;
import io.flowcatalyst.platform.function.artifact.ArtifactStores;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.OciArtifactStore;
import io.flowcatalyst.platform.function.artifact.RegistryCredentials;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/// Assembles and runs the host process (spec `function-host-listener.md`
/// §5): `HostEnv → ArtifactStores → Reconciler + ReconcileLoop → FnHttpServer`.
/// No `main` here by design — the process entry point, metrics and the
/// Dockerfile are slice D5.
public final class FnHost implements AutoCloseable {

    private final HostEnv env;
    private final FunctionRegistry registry;
    private final Reconciler reconciler;
    private final ReconcileLoop loop;
    private volatile FnHttpServer server;

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
    }

    /// Test/composition seam: an already-built [Reconciler] (a fake control
    /// plane, an in-memory artifact store, …) instead of the real HTTP
    /// assembly above.
    public FnHost(HostEnv env, Reconciler reconciler, FunctionRegistry registry) {
        this.env = Objects.requireNonNull(env, "env");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        this.loop = new ReconcileLoop(reconciler);
    }

    /// Spec §5: `start()` binds only after the first reconcile attempt
    /// (success or failure) — a host must never answer 404 for a function it
    /// simply has not heard of yet.
    public void start() {
        reconciler.reconcileOnce(Instant.now());
        loop.start();
        server = FnHttpServer.start(reconciler,
                FnHttpServer.Options.of(env.port(), env.maxConcurrency(), env.platformUrl()));
    }

    public int port() {
        FnHttpServer s = server;
        return s == null ? -1 : s.port();
    }

    public Reconciler reconciler() {
        return reconciler;
    }

    /// Spec §5: `drain()` (heartbeat `DRAINING`), stop accepting, wait for
    /// in-flight up to `FC_DRAIN_TIMEOUT_SECONDS` (default 60), close the
    /// loop, close every loaded function.
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
    }
}

package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.json.JavalinJsonMapper;
import io.javalin.Javalin;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/// The single orchestrator fc-server and fcdev both call (Go `server.Run`):
///
///   - build the API app and wire the platform aggregates ([Mode.Platform])
///   - mount the router HTTP surface under `routerHttpPrefix` (when `routerEnabled`)
///   - spawn the background subsystems (scheduler, stream, outbox, router engine, mcp, purger)
///   - bind the API + metrics (+ optional MCP) listeners
///   - on [#stop()] (SIGTERM / fcdev stop): stop accepting, drain, stop subsystems
///
/// @param mode what this instance is: the platform API over a database, a
///             database-backed worker, or a router/MCP-only node — the pool
///             travels inside the mode, so "platform enabled but no pool" is
///             not a state this record can be in. The entry points derive it
///             from `FC_PLATFORM_ENABLED` and the other toggles; the record
///             refuses an [Env] that disagrees with it.
/// @param spa  the embedded SPA, served for anything no API route claims
///             ([Spa.Embedded]) or nothing at all ([Spa.None] — a router-only
///             instance must not serve the dashboard)
public record Server(Env env, Mode mode, Spa spa, PrometheusRegistry registry) {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    public Server {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(spa, "spa");
        Objects.requireNonNull(registry, "registry");
        if (env.platformEnabled() != (mode instanceof Mode.Platform)) {
            throw new IllegalArgumentException("FC_PLATFORM_ENABLED=" + env.platformEnabled()
                    + " but the server mode is " + mode.getClass().getSimpleName());
        }
    }

    /// What the instance runs, and therefore whether it owns a database pool.
    public sealed interface Mode permits Mode.Platform, Mode.Worker, Mode.RouterOnly {

        /// The platform API is served from `pool`; any enabled DB-backed
        /// background subsystem shares it.
        record Platform(DataSource pool) implements Mode {
            public Platform {
                Objects.requireNonNull(pool, "pool");
            }
        }

        /// DB-backed background subsystems (scheduler, stream, outbox …)
        /// without the platform API — the worker tier.
        record Worker(DataSource pool) implements Mode {
            public Worker {
                Objects.requireNonNull(pool, "pool");
            }
        }

        /// No database: the router and/or MCP surfaces only.
        enum RouterOnly implements Mode {
            INSTANCE
        }

        static Mode routerOnly() {
            return RouterOnly.INSTANCE;
        }
    }

    /// Whether the embedded Vue SPA is mounted as the catch-all.
    public sealed interface Spa permits Spa.Embedded, Spa.None {

        /// Serve `frontend` for every `GET` no API route claims.
        record Embedded(Frontend frontend) implements Spa {
            public Embedded {
                Objects.requireNonNull(frontend, "frontend");
            }
        }

        /// No SPA: unknown paths get the 404 envelope.
        enum None implements Spa {
            INSTANCE
        }

        static Spa none() {
            return None.INSTANCE;
        }
    }

    /// A started server: both listeners bound, subsystems running.
    public static final class Running {
        private final Javalin api;
        private final Metrics.Running metrics;
        private final CountDownLatch stopped = new CountDownLatch(1);

        private Running(Javalin api, Metrics.Running metrics) {
            this.api = api;
            this.metrics = metrics;
        }

        public int apiPort() {
            return api.port();
        }

        public int metricsPort() {
            return metrics.port();
        }

        /// Graceful stop: listeners first (Jetty drains in-flight requests
        /// within the grace period), then background subsystems.
        public void stop() {
            try {
                api.stop();
                metrics.stop();
                // TODO(port): stop scheduler / stream / outbox / router / mcp and wait for them
                LOG.info("server stopped");
            } finally {
                stopped.countDown();
            }
        }

        /// Blocks until [#stop()] completes.
        public void awaitStop() throws InterruptedException {
            stopped.await();
        }
    }

    public Running start() {
        Javalin api = buildApi();

        // ── background subsystems ───────────────────────────────────────────
        // TODO(port): purger (platform), scheduler, scheduled-job scheduler, stream processor,
        //   outbox processor, router engine, MCP — each leader-gated as in subsystems.go.
        var toggles = List.of(
                new Toggle("scheduler", env.schedulerEnabled()),
                new Toggle("scheduled-job", env.scheduledJobEnabled()),
                new Toggle("stream", env.streamEnabled()),
                new Toggle("outbox", env.outboxEnabled()),
                new Toggle("mcp", env.mcpEnabled()));
        for (var toggle : toggles) {
            if (toggle.enabled()) LOG.warn("{} subsystem not yet ported; toggle ignored", toggle.subsystem());
        }

        // ── listeners ───────────────────────────────────────────────────────
        var metrics = new Metrics(env, registry).start();
        LOG.info("metrics server listening addr=:{}", env.metricsPort());
        api.start(env.apiPort());
        LOG.info("api server listening addr=:{}", env.apiPort());
        return new Running(api, metrics);
    }

    /// The fully wired (not yet started) API app — exposed so the contract
    /// tests can enumerate the registered routes without binding a port.
    Javalin buildApi() {
        return Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.concurrency.useVirtualThreads = true;
            cfg.jsonMapper(new JavalinJsonMapper());
            // Unknown non-GET paths answer with the 404 envelope (Go's chi hands every unmatched
            // method to the SPA NotFound handler — index.html for a POST — which nobody relies on).
            cfg.http.prefer405over404 = false;
            cfg.jetty.modifyServer(server -> server.setStopTimeout(SHUTDOWN_GRACE.toMillis()));

            cfg.routes.get("/health", Health::handle);

            switch (mode) {
                case Mode.Platform(var pool) -> new Platform(env, pool, loadSigningKeys()).register(cfg.routes);
                case Mode.Worker _, Mode.RouterOnly _ -> {
                    // no platform API on this instance
                }
            }
            if (env.routerEnabled()) {
                // TODO(port): MountRouterHTTP under env.routerHttpPrefix() (BasicAuth, monitoring API,
                //   dashboard, /metrics alias) + the router engine.
                LOG.warn("router HTTP surface not yet ported; FC_ROUTER_ENABLED ignored");
            }
            switch (spa) {
                case Spa.Embedded(var frontend) -> frontend.register(cfg.routes);
                case Spa.None _ -> {
                    // unknown paths get the 404 envelope
                }
            }
        });
    }

    private SigningKeys loadSigningKeys() {
        var signingKeys = SigningKeys.load(env);
        if (signingKeys.ephemeral()) {
            LOG.warn("no JWT signing key configured — using an EPHEMERAL RSA key; tokens will not survive a restart");
        }
        return signingKeys;
    }

    /// A subsystem toggle from [Env] that has no implementation behind it yet.
    private record Toggle(String subsystem, boolean enabled) {
    }
}

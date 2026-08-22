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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/// The single orchestrator fc-server and fcdev both call (Go `server.Run`):
///
///   - build the API app and wire the platform aggregates (when `platformEnabled`)
///   - mount the router HTTP surface under `routerHttpPrefix` (when `routerEnabled`)
///   - spawn the background subsystems (scheduler, stream, outbox, router engine, mcp, purger)
///   - bind the API + metrics (+ optional MCP) listeners
///   - on [#stop()] (SIGTERM / fcdev stop): stop accepting, drain, stop subsystems
///
/// @param fallback the embedded SPA, served for anything no API route claims
///                 (fc-server: only when the platform is enabled and the
///                 frontend was built in; a router-only instance must not
///                 serve the dashboard)
public record Server(Env env, DataSource pool, Optional<Frontend> fallback, PrometheusRegistry registry) {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    public Server {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(registry, "registry");
    }

    /// A started server: both listeners bound, subsystems running.
    public static final class Running {
        private final Javalin api;
        private final Metrics metrics;
        private final CountDownLatch stopped = new CountDownLatch(1);

        private Running(Javalin api, Metrics metrics) {
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
        var metrics = new Metrics(env, registry);
        metrics.start();
        LOG.info("metrics server listening addr=:{}", env.metricsPort());
        api.start(env.apiPort());
        LOG.info("api server listening addr=:{}", env.apiPort());
        return new Running(api, metrics);
    }

    /// The fully wired (not yet started) API app — exposed so the contract
    /// tests can enumerate the registered routes without binding a port.
    Javalin buildApi() {
        SigningKeys signingKeys = env.platformEnabled() ? SigningKeys.load(env) : null;
        if (signingKeys != null && signingKeys.ephemeral()) {
            LOG.warn("no JWT signing key configured — using an EPHEMERAL RSA key; tokens will not survive a restart");
        }

        return Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.concurrency.useVirtualThreads = true;
            cfg.jsonMapper(new JavalinJsonMapper());
            // Unknown non-GET paths answer with the 404 envelope (Go's chi hands every unmatched
            // method to the SPA NotFound handler — index.html for a POST — which nobody relies on).
            cfg.http.prefer405over404 = false;
            cfg.jetty.modifyServer(server -> server.setStopTimeout(SHUTDOWN_GRACE.toMillis()));

            cfg.routes.get("/health", Health::handle);

            if (env.platformEnabled()) {
                if (pool == null) throw new IllegalStateException("platform enabled but no database pool");
                new Platform(env, pool, signingKeys).register(cfg.routes);
            }
            if (env.routerEnabled()) {
                // TODO(port): MountRouterHTTP under env.routerHttpPrefix() (BasicAuth, monitoring API,
                //   dashboard, /metrics alias) + the router engine.
                LOG.warn("router HTTP surface not yet ported; FC_ROUTER_ENABLED ignored");
            }
            fallback.ifPresent(f -> f.register(cfg.routes));
        });
    }

    /// A subsystem toggle from [Env] that has no implementation behind it yet.
    private record Toggle(String subsystem, boolean enabled) {
    }
}

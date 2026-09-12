package io.flowcatalyst.server;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.platform.dispatch.RouterConfigDocumentBuilder;
import io.flowcatalyst.platform.shared.json.Json;
import io.javalin.Javalin;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

/// The second listener (`FC_METRICS_PORT`, default 9090): `/health`,
/// `/ready` and `/metrics` — the "is the binary up" surface every
/// deployment scrapes. Go served a placeholder string at `/metrics` and kept
/// the real series under `/router/metrics`; the agreed tidy-up is to expose
/// the real Prometheus registry here (the router alias stays for existing
/// scrapes).
///
/// Since R3 (`docs/spec/deployed-dispatch.md` §3) this listener also serves
/// one application endpoint, not just health/metrics: `/api/dispatch/router-config`,
/// unauthenticated, when [#dispatchRouterConfig] is non-null. It lives here
/// rather than on the public API listener because 8080 is ALB-facing and the
/// document lists client identifiers, pool codes and queue URLs — this
/// listener is reached only through a Service Connect alias, never the ALB.
public final class Metrics {

    private final Env env;
    private final PrometheusRegistry registry;
    private final RouterConfigDocumentBuilder dispatchRouterConfig;

    public Metrics(Env env, PrometheusRegistry registry) {
        this(env, registry, null);
    }

    /// @param dispatchRouterConfig builds the `/api/dispatch/router-config`
    ///                             document; `null` when this instance is
    ///                             not in platform mode, in which case the
    ///                             route is not registered at all (never
    ///                             registered-then-failing).
    public Metrics(Env env, PrometheusRegistry registry, RouterConfigDocumentBuilder dispatchRouterConfig) {
        this.env = Objects.requireNonNull(env, "env");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dispatchRouterConfig = dispatchRouterConfig;
    }

    /// The shared registry every subsystem registers its collectors with.
    public PrometheusRegistry registry() {
        return registry;
    }

    /// Binds the listener; the bound port and `stop()` live on the handle,
    /// so there is no "started?" state to get wrong.
    public Running start() {
        var formats = ExpositionFormats.init();
        var app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.concurrency.useVirtualThreads = true;
            var routes = io.flowcatalyst.http.javalin.JavalinAdapter.install(cfg);
            routes.get("/health", Health.noChecks()::handle);
            routes.get("/ready", this::ready);
            routes.get("/metrics", ctx -> scrape(ctx, formats));
            if (dispatchRouterConfig != null) {
                routes.get("/api/dispatch/router-config", this::routerConfig);
            }
        }).start(env.metricsPort());
        return new Running(app);
    }

    /// A bound metrics listener.
    public static final class Running {
        private final Javalin app;

        private Running(Javalin app) {
            this.app = app;
        }

        /// The bound port (differs from the configured one when it was 0, e.g. in tests).
        public int port() {
            return app.port();
        }

        public void stop() {
            app.stop();
        }
    }

    /// `{"status":"ready", …every subsystem toggle…}` — keys in Go's
    /// (alphabetical map) order, newline-terminated like `json.Encoder`.
    private void ready(Exchange ctx) {
        var body = new LinkedHashMap<String, Object>();
        body.put("mcp", env.mcpEnabled());
        body.put("outbox", env.outboxEnabled());
        body.put("platform", env.platformEnabled());
        body.put("router", env.routerEnabled());
        body.put("scheduled_job", env.scheduledJobEnabled());
        body.put("scheduler", env.schedulerEnabled());
        body.put("status", "ready");
        body.put("stream", env.streamEnabled());
        ctx.contentType("application/json").result(Json.writeLine(body));
    }

    /// `GET /api/dispatch/router-config` (R3): the platform's own
    /// `{processingPools, queues}` document, unauthenticated — see the class
    /// doc for why this listener rather than the public one.
    private void routerConfig(Exchange ctx) {
        ctx.contentType("application/json").result(Json.writeLine(dispatchRouterConfig.build()));
    }

    private void scrape(Exchange ctx, ExpositionFormats formats) throws IOException {
        var writer = formats.findWriter(ctx.header("Accept"));
        var out = new ByteArrayOutputStream();
        writer.write(out, registry.scrape());
        ctx.contentType(writer.getContentType()).result(out.toByteArray());
    }
}

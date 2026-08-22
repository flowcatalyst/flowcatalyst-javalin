package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.json.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;

/// The second listener (`FC_METRICS_PORT`, default 9090): `/health`,
/// `/ready` and `/metrics` — the "is the binary up" surface every
/// deployment scrapes. Go served a placeholder string at `/metrics` and kept
/// the real series under `/router/metrics`; the agreed tidy-up is to expose
/// the real Prometheus registry here (the router alias stays for existing
/// scrapes).
public final class Metrics {

    private final Env env;
    private final PrometheusRegistry registry;
    private final ExpositionFormats formats = ExpositionFormats.init();
    private Javalin app;

    public Metrics(Env env, PrometheusRegistry registry) {
        this.env = env;
        this.registry = registry;
    }

    /// The shared registry every subsystem registers its collectors with.
    public PrometheusRegistry registry() {
        return registry;
    }

    public void start() {
        app = Javalin.create(cfg -> {
            cfg.startup.showJavalinBanner = false;
            cfg.concurrency.useVirtualThreads = true;
            cfg.routes.get("/health", Health::handle);
            cfg.routes.get("/ready", this::ready);
            cfg.routes.get("/metrics", this::scrape);
        }).start(env.metricsPort());
    }

    public void stop() {
        if (app != null) app.stop();
    }

    /// The bound port (differs from the configured one when it was 0, e.g. in tests).
    public int port() {
        return app.port();
    }

    /// `{"status":"ready", …every subsystem toggle…}` — keys in Go's
    /// (alphabetical map) order, newline-terminated like `json.Encoder`.
    private void ready(Context ctx) {
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

    private void scrape(Context ctx) throws IOException {
        var writer = formats.findWriter(ctx.header("Accept"));
        var out = new ByteArrayOutputStream();
        writer.write(out, registry.scrape());
        ctx.contentType(writer.getContentType()).result(out.toByteArray());
    }
}

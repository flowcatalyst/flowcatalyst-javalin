package io.flowcatalyst.server;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.RequestWorkers;
import io.flowcatalyst.http.vertx.VertxListener;
import io.flowcatalyst.platform.shared.json.Json;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The second listener (`FC_METRICS_PORT`, default 9090): `/health`,
/// `/ready` and `/metrics` — the "is the binary up" surface every
/// deployment scrapes. Go served a placeholder string at `/metrics` and kept
/// the real series under `/router/metrics`; the agreed tidy-up is to expose
/// the real Prometheus registry here (the router alias stays for existing
/// scrapes). A second [VertxListener] on the same host, its own event loop
/// and worker: this listener is deliberately independent of the API
/// listener's admission (`docs/spec/vertx-listener.md` §1) — a saturated API
/// must not make the process look unreachable to a liveness probe.
public final class Metrics {

    private final Env env;
    private final PrometheusRegistry registry;

    public Metrics(Env env, PrometheusRegistry registry) {
        this.env = Objects.requireNonNull(env, "env");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /// The shared registry every subsystem registers its collectors with.
    public PrometheusRegistry registry() {
        return registry;
    }

    /// Binds the listener; the bound port and `stop()` live on the handle,
    /// so there is no "started?" state to get wrong.
    public Running start() {
        var formats = ExpositionFormats.init();
        // Every route unbounded (Group.NO_DB): a liveness/readiness/metrics
        // scrape must never queue behind another, and none of these three
        // touch the database — a single MAIN pool slot exists only because
        // RequestWorkers always has one, never because anything runs in it.
        // Bound to every interface (unlike the loopback-only outbox admin
        // API) — an external scraper (a different pod/host) has to reach it —
        // and plain HTTP/1.1 only (`docs/spec/http-transport.md` §1
        // "Metrics stays a plain HTTP/1.1 Jetty as today"; `VertxListener.Options.local`
        // would default h2c on, which this listener was never meant to have).
        var options = new VertxListener.Options("0.0.0.0", env.metricsPort(), false,
                Duration.ofSeconds(30), Duration.ofSeconds(130), Duration.ofSeconds(5),
                RequestWorkers.of(Map.of()), Optional.empty());
        var listener = VertxListener.start(options, routes -> {
            var noDb = routes.in(Group.NO_DB);
            noDb.get("/health", Health.noChecks()::handle);
            noDb.get("/ready", this::ready);
            noDb.get("/metrics", ctx -> scrape(ctx, formats));
        });
        return new Running(listener);
    }

    /// A bound metrics listener.
    public static final class Running {
        private final VertxListener listener;

        private Running(VertxListener listener) {
            this.listener = listener;
        }

        /// The bound port (differs from the configured one when it was 0, e.g. in tests).
        public int port() {
            return listener.port();
        }

        public void stop() {
            listener.close();
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

    private void scrape(Exchange ctx, ExpositionFormats formats) throws IOException {
        var writer = formats.findWriter(ctx.header("Accept"));
        var out = new ByteArrayOutputStream();
        writer.write(out, registry.scrape());
        ctx.contentType(writer.getContentType()).result(out.toByteArray());
    }
}

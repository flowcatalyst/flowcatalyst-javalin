package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// The observability listener (spec `function-host-process.md` §2):
/// `/health`, `/ready`, `/metrics` on `FC_METRICS_PORT` (default 9090, HTTP/1.1,
/// every interface) — its OWN [Vertx] instance and event loop, entirely
/// independent of [FnHttpServer]'s: a saturated function port must not make
/// the process look dead to a liveness probe (P7). Every handler here runs
/// directly on the event loop and does no I/O of its own (readiness is a few
/// volatile reads; a Prometheus scrape is in-memory) — there is deliberately
/// no virtual-thread dispatch, and so nothing here can ever block the loop.
public final class FnObservability implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FnObservability.class);
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private static final byte[] HEALTH_BODY = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOT_FOUND_BODY =
            "{\"error\":\"NOT_FOUND\",\"message\":\"not found\"}".getBytes(StandardCharsets.UTF_8);

    private final Vertx vertx;
    private final HttpServer httpServer;
    private final int port;

    /// @param eventLoopPoolSize a test-only seam, same reasoning as
    ///                          [FnHttpServer.Options#eventLoopPoolSize] —
    ///                          production never overrides Vert.x's own default
    public record Options(String host, int port, int eventLoopPoolSize) {
        public Options {
            Objects.requireNonNull(host, "host");
            if (eventLoopPoolSize <= 0) {
                throw new IllegalArgumentException("eventLoopPoolSize must be positive: " + eventLoopPoolSize);
            }
        }

        public static Options of(int port) {
            return new Options("0.0.0.0", port, VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE);
        }
    }

    private FnObservability(Vertx vertx, HttpServer httpServer, int port) {
        this.vertx = vertx;
        this.httpServer = httpServer;
        this.port = port;
    }

    /// Builds and binds. Returns once the socket is listening.
    public static FnObservability start(Reconciler reconciler, PrometheusRegistry registry, Options options) {
        Objects.requireNonNull(reconciler, "reconciler");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(options, "options");
        ExpositionFormats formats = ExpositionFormats.init();

        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(options.eventLoopPoolSize()));
        HttpServerOptions serverOptions = new HttpServerOptions().setHost(options.host()).setPort(options.port());
        HttpServer server = vertx.createHttpServer(serverOptions)
                .requestHandler(req -> handle(req, reconciler, registry, formats));
        try {
            server.listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            vertx.close();
            throw new IllegalStateException("binding the function host observability listener on "
                    + options.host() + ":" + options.port(), e);
        }
        return new FnObservability(vertx, server, server.actualPort());
    }

    public int port() {
        return port;
    }

    private static void handle(HttpServerRequest req, Reconciler reconciler, PrometheusRegistry registry,
                                ExpositionFormats formats) {
        if (!"GET".equals(req.method().name())) {
            respond(req, 404, "application/json", NOT_FOUND_BODY);
            return;
        }
        switch (req.path()) {
            case "/health" -> respond(req, 200, "application/json", HEALTH_BODY);
            case "/ready" -> ready(req, reconciler);
            case "/metrics" -> scrape(req, registry, formats);
            default -> respond(req, 404, "application/json", NOT_FOUND_BODY);
        }
    }

    /// Spec §2: 200 once the first reconcile has SUCCEEDED and the host is
    /// not draining; else 503 naming which of `STARTING`/`DRAINING`/
    /// `PLATFORM_UNREACHABLE` applies — [Reconciler.Readiness]'s own names.
    private static void ready(HttpServerRequest req, Reconciler reconciler) {
        Reconciler.Readiness readiness = reconciler.readiness();
        if (readiness == Reconciler.Readiness.READY) {
            respond(req, 200, "application/json", HEALTH_BODY);
            return;
        }
        byte[] body = ("{\"status\":\"" + readiness.name() + "\"}").getBytes(StandardCharsets.UTF_8);
        respond(req, 503, "application/json", body);
    }

    private static void scrape(HttpServerRequest req, PrometheusRegistry registry, ExpositionFormats formats) {
        try {
            var writer = formats.findWriter(req.getHeader("Accept"));
            var out = new ByteArrayOutputStream();
            writer.write(out, registry.scrape());
            respond(req, 200, writer.getContentType(), out.toByteArray());
        } catch (Exception e) {
            LOG.atWarn().setMessage("Prometheus scrape failed").setCause(e).log();
            respond(req, 500, "application/json",
                    "{\"error\":\"INTERNAL\",\"message\":\"scrape failed\"}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void respond(HttpServerRequest req, int status, String contentType, byte[] body) {
        req.response().setStatusCode(status).putHeader("Content-Type", contentType).end(Buffer.buffer(body));
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }

    public void close(Duration timeout) {
        try {
            httpServer.close().toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.atWarn().setMessage("closing the observability listener did not complete cleanly").log();
        }
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.atWarn().setMessage("closing Vert.x (observability) did not complete cleanly").log();
        }
    }
}

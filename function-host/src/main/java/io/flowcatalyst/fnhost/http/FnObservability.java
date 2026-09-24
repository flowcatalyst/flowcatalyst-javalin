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
import java.util.function.BooleanSupplier;

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

        /// The same options bound on `newHost` (see `FnHttpServer.Options#withHost`).
        public Options withHost(String newHost) {
            return new Options(newHost, port, eventLoopPoolSize);
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

    /// Convenience overload for callers that do not care about §3 item 3's
    /// LISTENER_DOWN/RECONCILER_DOWN states (most existing tests, and any
    /// caller that never binds a separate function listener at all) —
    /// behaves exactly as before that item existed: the listener is always
    /// considered bound, the loop always considered alive, and start-up
    /// always considered complete.
    public static FnObservability start(Reconciler reconciler, PrometheusRegistry registry, Options options) {
        return start(reconciler, registry, options, () -> true, () -> true, () -> true);
    }

    /// Builds and binds. Returns once the socket is listening.
    ///
    /// @param listenerBound      `function-host-process.md` §3 item 3: live
    ///                           read of whether [FnHttpServer#start] has
    ///                           returned — [io.flowcatalyst.fnhost.FnHost]'s
    ///                           own `() -> server != null`
    /// @param reconcileLoopAlive [ReconcileLoop#isAlive]
    /// @param startupComplete    true once [io.flowcatalyst.fnhost.FnHost#start]
    ///                           itself has returned — `/health` stays 200
    ///                           unconditionally before this (a slow first
    ///                           load must not kill a liveness probe), and
    ///                           reflects listener/loop health after
    public static FnObservability start(Reconciler reconciler, PrometheusRegistry registry, Options options,
                                         BooleanSupplier listenerBound, BooleanSupplier reconcileLoopAlive,
                                         BooleanSupplier startupComplete) {
        Objects.requireNonNull(reconciler, "reconciler");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listenerBound, "listenerBound");
        Objects.requireNonNull(reconcileLoopAlive, "reconcileLoopAlive");
        Objects.requireNonNull(startupComplete, "startupComplete");
        ExpositionFormats formats = ExpositionFormats.init();

        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(options.eventLoopPoolSize()));
        HttpServerOptions serverOptions = new HttpServerOptions().setHost(options.host()).setPort(options.port());
        HttpServer server = vertx.createHttpServer(serverOptions)
                .requestHandler(req -> handle(req, reconciler, registry, formats, listenerBound, reconcileLoopAlive,
                        startupComplete));
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
                                ExpositionFormats formats, BooleanSupplier listenerBound,
                                BooleanSupplier reconcileLoopAlive, BooleanSupplier startupComplete) {
        if (!"GET".equals(req.method().name())) {
            respond(req, 404, "application/json", NOT_FOUND_BODY);
            return;
        }
        switch (req.path()) {
            case "/health" -> health(req, startupComplete, listenerBound, reconcileLoopAlive);
            case "/ready" -> ready(req, reconciler, listenerBound, reconcileLoopAlive);
            case "/metrics" -> scrape(req, registry, formats);
            default -> respond(req, 404, "application/json", NOT_FOUND_BODY);
        }
    }

    /// Spec §2/§3 item 3: 200 once the first reconcile has SUCCEEDED, the
    /// host is not draining, the FUNCTION listener is bound and the
    /// reconcile loop's thread is alive; else 503 naming which of
    /// `STARTING`/`DRAINING`/`PLATFORM_UNREACHABLE`/`LISTENER_DOWN`/
    /// `RECONCILER_DOWN` applies — [Reconciler.Readiness]'s own names.
    /// Either way the body carries a `memory` object (docs/spec/jvm-memory.md
    /// §4) — the split `docker/jvm-opts.sh` actually fenced this process
    /// into, read live off the running JVM, so an operator never has to
    /// shell into the container to see it.
    private static void ready(HttpServerRequest req, Reconciler reconciler, BooleanSupplier listenerBound,
                               BooleanSupplier reconcileLoopAlive) {
        Reconciler.Readiness readiness =
                reconciler.readiness(listenerBound.getAsBoolean(), reconcileLoopAlive.getAsBoolean());
        String status = readiness == Reconciler.Readiness.READY ? "UP" : readiness.name();
        int statusCode = readiness == Reconciler.Readiness.READY ? 200 : 503;
        respond(req, statusCode, "application/json", readyBody(status, FnMemorySnapshot.capture()));
    }

    /// §3 item 3: liveness, not readiness — a slow first reconcile must
    /// never fail this (`startupComplete` false ⇒ always 200, regardless of
    /// listener/loop state, which cannot even be meaningfully true yet), but
    /// once start-up HAS completed, `/health` tells the truth about whether
    /// this process can still do its job: 503 `LISTENER_DOWN` if the
    /// FUNCTION port somehow is not bound, 503 `RECONCILER_DOWN` if the
    /// reconcile loop's thread has died — either is a reason for ECS to
    /// replace the task (`docs/deployments.md`). Deliberately NOT draining-
    /// or platform-outage-aware: those are the process still doing its job
    /// (a graceful shutdown in progress, or a control-plane outage neither
    /// of which this process caused) — only `/ready` reports those.
    private static void health(HttpServerRequest req, BooleanSupplier startupComplete,
                                BooleanSupplier listenerBound, BooleanSupplier reconcileLoopAlive) {
        if (!startupComplete.getAsBoolean()) {
            respond(req, 200, "application/json", HEALTH_BODY);
            return;
        }
        if (!listenerBound.getAsBoolean()) {
            respond(req, 503, "application/json", healthBody("LISTENER_DOWN"));
            return;
        }
        if (!reconcileLoopAlive.getAsBoolean()) {
            respond(req, 503, "application/json", healthBody("RECONCILER_DOWN"));
            return;
        }
        respond(req, 200, "application/json", HEALTH_BODY);
    }

    private static byte[] healthBody(String status) {
        return ("{\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readyBody(String status, FnMemorySnapshot.Info memory) {
        StringBuilder memoryJson = new StringBuilder("{");
        boolean first = true;
        first = appendOptional(memoryJson, "limitBytes", memory.limitBytes(), first);
        first = appendField(memoryJson, "heapMaxBytes", memory.heapMaxBytes(), first);
        first = appendOptional(memoryJson, "metaspaceMaxBytes", memory.metaspaceMaxBytes(), first);
        appendOptional(memoryJson, "directMaxBytes", memory.directMaxBytes(), first);
        memoryJson.append('}');
        String body = "{\"status\":\"" + status + "\",\"memory\":" + memoryJson + "}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean appendOptional(StringBuilder sb, String name, Long value, boolean first) {
        if (value == null) {
            return first;
        }
        return appendField(sb, name, value, first);
    }

    private static boolean appendField(StringBuilder sb, String name, long value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(name).append("\":").append(value);
        return false;
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

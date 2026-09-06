package io.flowcatalyst.http.vertx;

import io.flowcatalyst.http.Admission;
import io.flowcatalyst.http.Budgets;
import io.flowcatalyst.http.ExceptionMappers;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.HttpException;
import io.flowcatalyst.http.RequestWorkers;
import io.flowcatalyst.http.RouteRegistry;
import io.flowcatalyst.http.Routes;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.postgresql.jdbc.PgConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The Vert.x listener (`docs/spec/vertx-listener.md`): one event loop, one
/// `HttpServer`, one `Router`; every request's seam chain runs on its own
/// virtual thread (dispatch model B) and the buffered response is written
/// back on the request's context. Time is the loop's: the deadline is a Vert.x
/// timer that cancels the request's queries and interrupts its thread.
public final class VertxListener implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(VertxListener.class);
    /// Javalin's default `maxRequestSize`, kept for parity.
    static final long MAX_BODY_BYTES = 1_000_000;
    /// How long a cancelled query gets to wake its thread before the interrupt fallback.
    static final Duration CANCEL_GRACE = Duration.ofMillis(250);
    static final byte[] DEADLINE_BODY = "{\"error\":\"DEADLINE\",\"message\":\"the request exceeded its deadline\"}".getBytes(StandardCharsets.UTF_8);

    /// What the listener needs beyond routes. `deadline` is the product default
    /// (30 s); `DISPATCH` routes get `dispatchDeadline` (130 s) — both derived,
    /// neither configurable (admission.md §4).
    public record Options(String host, int port, boolean h2c, Budgets budgets, Duration deadline,
                          Duration dispatchDeadline, Duration shutdownGrace, RequestWorkers workers) {
        public static Options local(int port, Budgets budgets) {
            return local(port, budgets, RequestWorkers.derived(io.flowcatalyst.platform.shared.database.Database.DEFAULT_POOL_SIZE - 2));
        }

        public static Options local(int port, Budgets budgets, RequestWorkers workers) {
            return new Options("127.0.0.1", port, true, budgets, Duration.ofSeconds(30), Duration.ofSeconds(130), Duration.ofSeconds(5), workers);
        }

        public Options withDeadline(Duration d) {
            return new Options(host, port, h2c, budgets, d, d, shutdownGrace, workers);
        }
    }

    private final Vertx vertx;
    private final HttpServer server;
    /// Kept for the failure path only; requests run on [RequestWorkers].
    private final ExecutorService handlers;
    private final VertxRoutes routes;
    private final Options options;
    private volatile int port;

    private VertxListener(Vertx vertx, HttpServer server, ExecutorService handlers, VertxRoutes routes, Options options, int port) {
        this.vertx = vertx;
        this.server = server;
        this.handlers = handlers;
        this.routes = routes;
        this.options = options;
        this.port = port;
    }

    /// Configures, mounts and binds. Returns once the socket is listening.
    public static VertxListener start(Options options, Consumer<Routes> configure) {
        return prepare(options, configure).listen();
    }

    /// Routes configured and mounted, socket not yet bound — so the registry
    /// can be read (`LockfileCoverageTest`) without serving, and `configure`
    /// (which constructs the platform) runs exactly once.
    public static final class Prepared {
        private final VertxListener listener;

        private Prepared(VertxListener listener) {
            this.listener = listener;
        }

        public RouteRegistry registry() {
            return listener.routes;
        }

        public VertxListener listen() {
            var l = listener;
            try {
                l.server.listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while binding the Vert.x listener", e);
            } catch (ExecutionException | TimeoutException e) {
                l.vertx.close();
                throw new IllegalStateException("binding the Vert.x listener on " + l.options.host() + ":" + l.options.port(), e.getCause() != null ? e.getCause() : e);
            }
            l.port = l.server.actualPort();
            return l;
        }

        /// Releases everything without ever having listened.
        public void discard() {
            listener.handlers.shutdown();
            listener.vertx.close();
        }
    }

    public static Prepared prepare(Options options, Consumer<Routes> configure) {
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        var routes = new VertxRoutes(new ExceptionMappers());
        configure.accept(routes);
        ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        var listener = new VertxListener[1];
        Router router = Router.router(vertx);
        for (VertxRoutes.Mount mount : routes.mounts()) {
            router.route(VertxRoutes.vertxPath(mount.pattern)).handler(rc -> {
                VertxRoutes.Grouped g = mount.byMethod.get(rc.request().method().name());
                // A method miss falls through to later routes (Javalin routes per
                // method, so a GET catch-all still wins); the last route is the 404.
                if (g == null) rc.next();
                else listener[0].dispatch(rc, g.group(), g.handler());
            });
        }
        router.route().last().handler(rc -> listener[0].dispatch(rc, Group.NO_DB, NOT_FOUND));
        router.route().failureHandler(rc -> {
            int status = rc.statusCode() > 0 ? rc.statusCode() : 500;
            Throwable failure = rc.failure();
            String message = failure != null && failure.getMessage() != null ? failure.getMessage() : "";
            listener[0].dispatch(rc, Group.NO_DB, x -> {
                throw new HttpException(status, message);
            });
        });
        var serverOptions = new HttpServerOptions()
                .setHost(options.host())
                .setPort(options.port())
                .setHttp2ClearTextEnabled(options.h2c());
        HttpServer server = vertx.createHttpServer(serverOptions).requestHandler(router);
        // The route handlers above capture `listener[0]`; no request can arrive
        // before `listen()`, so the reference is set in time.
        listener[0] = new VertxListener(vertx, server, handlers, routes, options, -1);
        return new Prepared(listener[0]);
    }

    private static final Handler NOT_FOUND = x -> {
        throw new HttpException(404, "Not found");
    };

    public int port() {
        return port;
    }

    public RouteRegistry registry() {
        return routes;
    }

    public Budgets budgets() {
        return options.budgets();
    }

    public RequestWorkers workers() {
        return options.workers();
    }

    /// Runs on the event loop: reads the body (capped at Javalin's
    /// `maxRequestSize`, the excess drained and remembered as "oversized" so the
    /// body accessors answer 413 lazily), captures the request's context and
    /// hands the whole chain to a fresh virtual thread.
    private void dispatch(RoutingContext rc, Group group, Handler handler) {
        Context requestContext = vertx.getOrCreateContext();
        var request = rc.request();
        if (rc.get(BODY_KEY) != null) {
            // Re-dispatched (failure handler after the body was already read).
            byte[] bytes = rc.get(BODY_KEY);
            boolean oversized = Boolean.TRUE.equals(rc.get(OVERSIZED_KEY));
            options.workers().submit(group, () -> runChain(rc, requestContext, group, handler, bytes, oversized));
            return;
        }
        String contentType = request.getHeader("Content-Type");
        if (contentType != null && contentType.regionMatches(true, 0, "application/x-www-form-urlencoded", 0, 33)) {
            request.setExpectMultipart(true);
        }
        Buffer buffer = Buffer.buffer();
        boolean[] oversized = new boolean[1];
        request.handler(chunk -> {
            if (oversized[0]) return;
            if (buffer.length() + chunk.length() > MAX_BODY_BYTES) {
                oversized[0] = true;
                return;
            }
            buffer.appendBuffer(chunk);
        });
        request.exceptionHandler(t -> {
            LOG.debug("request body read failed on {} {}", request.method(), request.path(), t);
            requestContext.runOnContext(v -> rc.response().setStatusCode(400).end());
        });
        request.endHandler(v -> {
            byte[] bytes = buffer.getBytes();
            rc.put(BODY_KEY, bytes);
            rc.put(OVERSIZED_KEY, oversized[0]);
            // Request-level admission (RequestWorkers): FIFO into the group's worker pool.
            options.workers().submit(group, () -> runChain(rc, requestContext, group, handler, bytes, oversized[0]));
        });
    }

    private static final String BODY_KEY = "io.flowcatalyst.http.vertx.body";
    private static final String OVERSIZED_KEY = "io.flowcatalyst.http.vertx.oversized";

    /// The seam chain on the request's virtual thread (spec §1 "dispatch
    /// model B"): group permit → admission scope → deadline → before* →
    /// handler → after*, then one hop back to the loop to write.
    private void runChain(RoutingContext rc, Context requestContext, Group group, Handler handler,
                          byte[] requestBody, boolean oversized) {
        var x = new VertxExchange(rc, requestBody, oversized);
        Thread me = Thread.currentThread();
        var admission = new Admission(rc.request().path());
        var deadlineFired = new AtomicBoolean();
        var finished = new AtomicBoolean();
        Duration deadline = group == Group.DISPATCH ? options.dispatchDeadline() : options.deadline();
        long timer = vertx.setTimer(deadline.toMillis(), id -> {
            deadlineFired.set(true);
            var held = admission.heldConnections();
            if (held.isEmpty()) {
                // Parked on a semaphore, a lock or a non-JDBC socket: interrupt wakes it.
                me.interrupt();
                return;
            }
            // Parked in a pgjdbc read: cancelQuery() wakes it with SQLSTATE 57014 and the
            // connection stays reusable (measured 8–12 ms). An interrupt landing on that
            // read instead closes the socket and the pool evicts the connection, so the
            // interrupt is only the fallback if the chain is still running afterwards.
            for (Connection c : held) cancelQuietly(c);
            vertx.setTimer(CANCEL_GRACE.toMillis(), id2 -> {
                if (!finished.get()) me.interrupt();
            });
        });
        try {
            ScopedValue.where(Admission.CURRENT, admission).call(() -> {
                chain(x, handler);
                return null;
            });
        } catch (Throwable t) {
            if (!deadlineFired.get()) LOG.error("unmapped failure on {} {}", x.method(), x.path(), t);
            if (!deadlineFired.get()) x.override(500, "{\"error\":\"INTERNAL\",\"message\":\"internal error\"}".getBytes(StandardCharsets.UTF_8));
        } finally {
            finished.set(true);
            vertx.cancelTimer(timer);
            Thread.interrupted();
        }
        if (deadlineFired.get()) x.override(503, DEADLINE_BODY);
        requestContext.runOnContext(v -> x.write(rc.response()));
    }

    private void chain(VertxExchange x, Handler handler) throws Exception {
        try {
            for (VertxRoutes.Before b : routes.befores()) {
                if (x.skipped()) break;
                if (b.matches(x.path())) b.handler().handle(x);
            }
            if (!x.skipped()) handler.handle(x);
        } catch (Throwable t) {
            map(x, t);
        }
        for (Handler after : routes.afters()) {
            try {
                after.handle(x);
            } catch (Throwable t) {
                map(x, t);
            }
        }
    }

    private void map(VertxExchange x, Throwable t) throws Exception {
        if (!(t instanceof Exception e)) throw new IllegalStateException(t);
        var mapper = routes.mappers().resolve(e);
        if (mapper.isEmpty()) throw e;
        mapper.get().handle(e, x);
    }

    private static void cancelQuietly(Connection c) {
        try {
            c.unwrap(PgConnection.class).cancelQuery();
        } catch (Exception e) {
            LOG.debug("cancelQuery on deadline failed", e);
        }
    }

    /// Drains in-flight requests for the grace period, then closes everything.
    @Override
    public void close() {
        try {
            server.shutdown(options.shutdownGrace()).toCompletionStage().toCompletableFuture()
                    .get(options.shutdownGrace().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("graceful shutdown of the Vert.x listener did not complete cleanly", e);
        }
        handlers.shutdown();
        options.workers().close();
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("closing Vert.x did not complete cleanly", e);
        }
    }
}

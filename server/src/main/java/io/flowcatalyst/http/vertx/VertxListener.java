package io.flowcatalyst.http.vertx;

import io.flowcatalyst.http.Admission;
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
import io.vertx.core.net.PfxOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.core.buffer.Buffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;
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
    /// `docs/spec/admission.md` §11.7 part B item 4: a request refused at a full group
    /// queue, or whose deadline fired while still queued (never got a worker).
    static final byte[] OVERLOADED_BODY = "{\"error\":\"OVERLOADED\",\"message\":\"the server is at capacity for this request group\"}".getBytes(StandardCharsets.UTF_8);

    /// What the listener needs beyond routes. `deadline` is the product default
    /// (30 s); `DISPATCH` routes get `dispatchDeadline` (130 s) — both derived,
    /// neither configurable (admission.md §4). `tls`, present only when
    /// `docs/spec/http-transport.md` §2 TLS material is configured, is a second
    /// `HttpServer` on the SAME `Router` (`docs/spec/vertx-listener.md` §1
    /// "Listeners"): TLS 1.2/1.3 with ALPN -> h2, http/1.1.
    public record Options(String host, int port, boolean h2c, Duration deadline,
                          Duration dispatchDeadline, Duration shutdownGrace, RequestWorkers workers,
                          Optional<Tls> tls) {
        /// Test convenience: a fixed, generous [RequestWorkers] sizing over every real
        /// group — not derived from any real [io.flowcatalyst.platform.shared.database.Pools],
        /// since most callers of this overload never touch a database at all.
        public static Options local(int port) {
            int n = io.flowcatalyst.platform.shared.database.Database.DEFAULT_POOL_SIZE - 2;
            int cores = Runtime.getRuntime().availableProcessors();
            return local(port, RequestWorkers.of(java.util.Map.of(
                    io.flowcatalyst.http.Group.API_WRITE, n,
                    io.flowcatalyst.http.Group.API_READ, 2 * n,
                    io.flowcatalyst.http.Group.BFF, 2 * n,
                    io.flowcatalyst.http.Group.DISPATCH, n,
                    io.flowcatalyst.http.Group.LOGIN, cores,
                    io.flowcatalyst.http.Group.OIDC, cores)));
        }

        public static Options local(int port, RequestWorkers workers) {
            return new Options("127.0.0.1", port, true, Duration.ofSeconds(30), Duration.ofSeconds(130),
                    Duration.ofSeconds(5), workers, Optional.empty());
        }

        public Options withDeadline(Duration d) {
            return new Options(host, port, h2c, d, d, shutdownGrace, workers, tls);
        }

        public Options withTls(Tls tls) {
            return new Options(host, port, h2c, deadline, dispatchDeadline, shutdownGrace, workers,
                    Optional.of(tls));
        }
    }

    /// TLS material for the second listener, framework-neutral (a
    /// [KeyStore] + its key password — `server.transport.TlsMaterial`'s
    /// shape, without this package depending on that one): PKCS#12 either
    /// way, keystore-loaded or PEM-assembled in memory
    /// (`docs/spec/http-transport.md` §2). Handed to Vert.x as
    /// [PfxOptions] bytes ([#pfxOptions]) since Vert.x has no
    /// `KeyStore`-object entry point.
    public record Tls(int port, KeyStore keyStore, char[] keyPassword) {
        PfxOptions pfxOptions() {
            try {
                var out = new ByteArrayOutputStream();
                keyStore.store(out, keyPassword);
                return new PfxOptions().setValue(Buffer.buffer(out.toByteArray())).setPassword(new String(keyPassword));
            } catch (GeneralSecurityException | java.io.IOException e) {
                throw new IllegalStateException("re-encoding the TLS key store for Vert.x", e);
            }
        }
    }

    private final Vertx vertx;
    private final HttpServer server;
    /// The TLS listener (`docs/spec/http-transport.md` §1), `null` when no
    /// TLS material is configured — same [Vertx], same `Router`, a second
    /// `HttpServer` on [Options.Tls#port].
    private final HttpServer tlsServer;
    /// Kept for the failure path only; requests run on [RequestWorkers].
    private final ExecutorService handlers;
    private final VertxRoutes routes;
    private final Options options;
    private volatile int port;
    private volatile int tlsPort;

    private VertxListener(Vertx vertx, HttpServer server, HttpServer tlsServer, ExecutorService handlers,
                          VertxRoutes routes, Options options, int port) {
        this.vertx = vertx;
        this.server = server;
        this.tlsServer = tlsServer;
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
                if (l.tlsServer != null) {
                    l.tlsServer.listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while binding the Vert.x listener", e);
            } catch (ExecutionException | TimeoutException e) {
                l.vertx.close();
                throw new IllegalStateException("binding the Vert.x listener on " + l.options.host() + ":" + l.options.port(), e.getCause() != null ? e.getCause() : e);
            }
            l.port = l.server.actualPort();
            if (l.tlsServer != null) l.tlsPort = l.tlsServer.actualPort();
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

        // The TLS listener (`docs/spec/http-transport.md` §1): TLS 1.2/1.3 with
        // ALPN -> h2, http/1.1, the SAME router as the plain listener — Vert.x
        // negotiates HTTP/2 over ALPN automatically once SSL is on, no separate
        // "enable h2" flag the way the plain listener needs for cleartext h2c.
        HttpServer tlsServer = options.tls().map(tls -> {
            var tlsServerOptions = new HttpServerOptions()
                    .setHost(options.host())
                    .setPort(tls.port())
                    .setSsl(true)
                    .setUseAlpn(true)
                    .setKeyCertOptions(tls.pfxOptions());
            return vertx.createHttpServer(tlsServerOptions).requestHandler(router);
        }).orElse(null);

        // The route handlers above capture `listener[0]`; no request can arrive
        // before `listen()`, so the reference is set in time.
        listener[0] = new VertxListener(vertx, server, tlsServer, handlers, routes, options, -1);
        return new Prepared(listener[0]);
    }

    private static final Handler NOT_FOUND = x -> {
        throw new HttpException(404, "Not found");
    };

    public int port() {
        return port;
    }

    /// `-1` when no TLS material is configured (`Options.tls()` empty).
    public int tlsPort() {
        return tlsServer == null ? -1 : tlsPort;
    }

    public RouteRegistry registry() {
        return routes;
    }

    public RequestWorkers workers() {
        return options.workers();
    }

    /// Every "platform" prefix (`Platform#isPlatformPath`, duplicated here rather than
    /// depended on — this package must not depend on `io.flowcatalyst.server`): an
    /// ungrouped registration under one of these defaults to `API_READ` (`docs/spec/admission.md`
    /// §11.7 "Ungrouped `/api/` registrations default to `API_READ`", extended to the whole
    /// authenticated surface — `/auth/`, `/oauth/`, `/bff/`, `/portal/`, `/.well-known/` all
    /// touch the database the same way an ungrouped `/api/` route does). Everything else
    /// (health, metrics, the SPA, OpenAPI documents, the router's own API, test fixtures)
    /// defaults to `NO_DB`: nothing to queue for, unbounded.
    private static final String[] PLATFORM_PREFIXES =
            {"/api/", "/auth/", "/oauth/", "/bff/", "/portal/", "/.well-known/"};

    private static Group effectiveGroup(Group declared, String path) {
        if (declared != null) return declared;
        for (String prefix : PLATFORM_PREFIXES) {
            if (path.startsWith(prefix)) return Group.API_READ;
        }
        return Group.NO_DB;
    }

    /// Runs on the event loop: reads the body (capped at Javalin's
    /// `maxRequestSize`, the excess drained and remembered as "oversized" so the
    /// body accessors answer 413 lazily), captures the request's context and
    /// hands the whole chain to a fresh virtual thread.
    private void dispatch(RoutingContext rc, Group group, Handler handler) {
        Context requestContext = vertx.getOrCreateContext();
        var request = rc.request();
        Group effectiveGroup = effectiveGroup(group, request.path());
        if (rc.get(BODY_KEY) != null) {
            // Re-dispatched (failure handler after the body was already read).
            byte[] bytes = rc.get(BODY_KEY);
            boolean oversized = Boolean.TRUE.equals(rc.get(OVERSIZED_KEY));
            submitOrReject(rc, requestContext, effectiveGroup, handler, bytes, oversized);
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
            submitOrReject(rc, requestContext, effectiveGroup, handler, bytes, oversized[0]);
        });
    }

    private static final String BODY_KEY = "io.flowcatalyst.http.vertx.body";
    private static final String OVERSIZED_KEY = "io.flowcatalyst.http.vertx.oversized";

    /// Request-level admission (`RequestWorkers`, `docs/spec/admission.md` §11.7 part B):
    /// FIFO into `group`'s worker pool. Runs on the event loop.
    ///
    /// Every queued request carries its own deadline, armed HERE — on the loop, before the
    /// request has a worker at all — not just once a worker starts running it (the earlier
    /// `runChain`-only timer left a request that never got a worker completely
    /// unprotected). `claimed` decides the race between "a worker took it" and "the
    /// deadline fired first": whichever wins runs exactly once. A full queue is the same
    /// outcome by a different path — `RequestWorkers#submit` already returned `false`
    /// without ever running `task`.
    private void submitOrReject(RoutingContext rc, Context requestContext, Group group, Handler handler,
                                byte[] requestBody, boolean oversized) {
        Duration deadline = group == Group.DISPATCH ? options.dispatchDeadline() : options.deadline();
        var claimed = new AtomicBoolean();
        long timerId = vertx.setTimer(deadline.toMillis(), id -> {
            if (claimed.compareAndSet(false, true)) {
                options.workers().markRejected(group);
                answerOverloaded(rc);
            }
        });
        boolean accepted = options.workers().submit(group, () -> {
            if (!claimed.compareAndSet(false, true)) return; // the queued-request deadline already answered
            vertx.cancelTimer(timerId);
            runChain(rc, requestContext, group, handler, requestBody, oversized);
        });
        if (!accepted) {
            if (claimed.compareAndSet(false, true)) {
                vertx.cancelTimer(timerId);
                answerOverloaded(rc);
            }
        }
    }

    /// `503`, `Retry-After: 1`, the platform's error envelope — a request refused at a
    /// full group queue or whose queued-request deadline fired first. Called on the event
    /// loop; never on a request's own virtual thread.
    private static void answerOverloaded(RoutingContext rc) {
        var resp = rc.response();
        if (resp.ended() || resp.closed()) return;
        resp.setStatusCode(503);
        resp.putHeader("Retry-After", "1");
        resp.putHeader("Content-Type", "application/json");
        resp.end(Buffer.buffer(OVERLOADED_BODY));
    }

    /// The seam chain on the request's virtual thread (spec §1 "dispatch
    /// model B"): admission scope → deadline → before* → handler → after*,
    /// then one hop back to the loop to write.
    private void runChain(RoutingContext rc, Context requestContext, Group group, Handler handler,
                          byte[] requestBody, boolean oversized) {
        var x = new VertxExchange(rc, group, requestBody, oversized);
        Thread me = Thread.currentThread();
        var admission = new Admission(rc.request().path(), group);
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
            if (!deadlineFired.get()) {
                LOG.atError().setMessage("unmapped failure")
                        .addKeyValue("method", x.method())
                        .addKeyValue("path", x.path())
                        .setCause(t)
                        .log();
            }
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
        if (tlsServer != null) {
            try {
                tlsServer.shutdown(options.shutdownGrace()).toCompletionStage().toCompletableFuture()
                        .get(options.shutdownGrace().toMillis() + 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                LOG.warn("graceful shutdown of the Vert.x TLS listener did not complete cleanly", e);
            }
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

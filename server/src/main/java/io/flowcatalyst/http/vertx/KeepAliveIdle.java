package io.flowcatalyst.http.vertx;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// Closes a connection that has had no request in flight for [#IDLE] (owner
/// ruling 2026-09-25, `docs/backlog.md` §"Overnight review" item 10).
///
/// The rule is phase-aware: the timer runs only while a connection has no request
/// in flight, and requests reset it, not bytes.
///
/// - **Between requests** it closes the idle keep-alive connection.
/// - **While a request is being read, handled or streamed** it never fires, so a
///   long invocation working silently or an SSE stream between events is not
///   cut. The listener's per-request deadline bounds reading a body.
/// - **Before a connection's first request** it cannot act. Vert.x surfaces an
///   HTTP/1.1 connection (`connectionHandler`) only once that request's headers
///   are complete, so a client that connects and sends nothing, or dribbles its
///   first headers, is not visible here. Vert.x's transport idle timeout would
///   see it, but it also counts silence during a long handler and would cut the
///   invocations the ruling protects. In production the ALB terminates client
///   connections and bounds this case. Recorded in the backlog (item 10).
///
/// 75 s is deliberately longer than the ALB's 60 s idle timeout. A backend that
/// closes a keep-alive connection first races the balancer's next request on it
/// and turns into intermittent 502s.
///
/// Threading: a connection's handlers (connection, request, response end/close,
/// the timer) all run on that connection's event loop, so each [State] is only
/// touched from one thread; the map itself is concurrent because connections on
/// different loops share it.
public final class KeepAliveIdle {

    /// Fixed (the "no tuning" rule): above the ALB's 60 s.
    public static final Duration IDLE = Duration.ofSeconds(75);

    private KeepAliveIdle() {
    }

    /// Installs the connection tracking on `server` and returns the request
    /// handler to give it: `requests`, wrapped so each request marks its
    /// connection busy until the response ends or closes.
    public static Handler<HttpServerRequest> install(Vertx vertx, HttpServer server, Handler<HttpServerRequest> requests) {
        return install(vertx, server, requests, IDLE);
    }

    /// [#install(Vertx, HttpServer, Handler)] with an explicit idle time, for tests.
    static Handler<HttpServerRequest> install(Vertx vertx, HttpServer server, Handler<HttpServerRequest> requests,
                                              Duration idle) {
        Objects.requireNonNull(vertx, "vertx");
        Objects.requireNonNull(requests, "requests");
        long idleMs = idle.toMillis();
        Map<HttpConnection, State> states = new ConcurrentHashMap<>();
        server.connectionHandler(conn -> {
            State state = new State(vertx, conn, idleMs);
            states.put(conn, state);
            state.arm();
            conn.closeHandler(v -> {
                state.disarm();
                states.remove(conn);
            });
        });
        return req -> {
            State state = states.get(req.connection());
            if (state != null) {
                state.started();
                // Whichever comes first: the response ends, or its stream/connection closes.
                // finished() counts each request once.
                boolean[] done = new boolean[1];
                Handler<Void> finish = v -> {
                    if (!done[0]) {
                        done[0] = true;
                        state.finished();
                    }
                };
                req.response().endHandler(finish);
                req.response().closeHandler(finish);
            }
            requests.handle(req);
        };
    }

    /// One connection's in-flight count and idle timer.
    private static final class State {
        private final Vertx vertx;
        private final HttpConnection conn;
        private final long idleMs;
        private int inFlight;
        private long timerId = -1;

        State(Vertx vertx, HttpConnection conn, long idleMs) {
            this.vertx = vertx;
            this.conn = conn;
            this.idleMs = idleMs;
        }

        void started() {
            inFlight++;
            disarm();
        }

        void finished() {
            if (inFlight > 0 && --inFlight == 0) {
                arm();
            }
        }

        void arm() {
            disarm();
            timerId = vertx.setTimer(idleMs, id -> {
                timerId = -1;
                if (inFlight == 0) {
                    conn.close();
                }
            });
        }

        void disarm() {
            if (timerId != -1) {
                vertx.cancelTimer(timerId);
                timerId = -1;
            }
        }
    }
}

package io.flowcatalyst.http.vertx;

import io.flowcatalyst.router.pool.MediationTransport;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Owns the Vert.x instance and HTTP client behind [VertxTransport]
/// (`docs/spec/router-h2.md` §5, owner ruling 2026-09-07): the router's
/// deployed-mode mediation transport, built once by `Router.start` and
/// closed by `Router.close`.
///
/// Deliberately its **own** [Vertx] — never the listener's, and the listener
/// may not even be a Vert.x one (Javalin is still an option) — whose event
/// loops are sized to the CPU quota ([#eventLoopsFor], at most [#MAX_EVENT_LOOPS]):
/// mediation calls are I/O-bound on the loops and never run application code
/// there.
///
/// ### Why the pool is tuned (measured departure from "no tuning")
///
/// Vert.x's defaults are one HTTP/2 connection per origin on one event loop. A
/// connection carries at most the server's concurrent-stream limit (250 for Go
/// servers, commonly 128 elsewhere), so with many pools delivering to one
/// target everything beyond that queued behind a single connection on a single
/// loop. At 100 queues x 64 workers (6,400 deliveries in flight) that capped the
/// router near 40k/s at 4 CPUs while it used only ~57% of them. Allowing
/// [#MAX_H2_CONNECTIONS_PER_ORIGIN] connections (opened lazily, only when the
/// existing ones are full) spread over the loops took 4 CPUs to ~69k/s, and
/// 1 CPU from 21k to 24k/s. The pool is not a tuning knob for operators: it
/// grows only as far as the load needs.
///
/// The composition root (`io.flowcatalyst.server.Router`) depends on this
/// class, never on `io.vertx.*` directly — `NoFrameworkLeakTest` forbids
/// that import anywhere outside this package.
public final class VertxMediationClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VertxMediationClient.class);

    /// The one number the ruling calls out explicitly. Everything else is
    /// Vert.x's own default (keep-alive, pipelining) except the HTTP/2 pool
    /// and event loops, which are sized as the class doc explains — no
    /// operator-facing knobs (`CLAUDE.md` "no tuning; defaults are the
    /// product").
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /// Upper bound on event loops: beyond this the loops stop being the limit
    /// (the sink and broker are) and each adds a thread to a small CPU quota.
    static final int MAX_EVENT_LOOPS = 4;

    /// Upper bound on HTTP/2 connections per origin. Connections are opened
    /// only when the existing ones are at the server's stream limit, so this
    /// costs nothing until the load needs it.
    static final int MAX_H2_CONNECTIONS_PER_ORIGIN = 32;

    /// Event loops for a quota of `cpus`: one per CPU, at least one, at most
    /// [#MAX_EVENT_LOOPS].
    static int eventLoopsFor(int cpus) {
        return Math.max(1, Math.min(MAX_EVENT_LOOPS, cpus));
    }

    private final Vertx vertx;
    private final HttpClient client;
    private final MediationTransport transport;

    private VertxMediationClient(Vertx vertx, HttpClient client, MediationTransport transport) {
        this.vertx = vertx;
        this.client = client;
        this.transport = transport;
    }

    public static VertxMediationClient start() {
        int loops = eventLoopsFor(Runtime.getRuntime().availableProcessors());
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(loops));
        var options = new HttpClientOptions()
                // Prior-knowledge h2c for http:// targets, ALPN for
                // https:// — the ruling this class exists for. The JDK
                // client (still used in dev mode, via JdkTransport) never
                // does h2c by prior knowledge, and never attempts the
                // `Upgrade: h2c` dance for a request with a body — every
                // mediation call is a POST with a body
                // (`docs/spec/router-h2.md` §5, finding Q7).
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false)
                .setUseAlpn(true)
                .setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        var pool = new PoolOptions()
                .setHttp2MaxSize(MAX_H2_CONNECTIONS_PER_ORIGIN)
                .setEventLoopSize(loops);
        HttpClient client = vertx.httpClientBuilder().with(options).with(pool).build();
        return new VertxMediationClient(vertx, client, new VertxTransport(client));
    }

    public MediationTransport transport() {
        return transport;
    }

    /// Closes the client, then the Vert.x instance it was the only user of.
    /// Best-effort: a slow shutdown is logged, not thrown — this runs from
    /// `Router.close`, which must not fail the rest of shutdown over it.
    @Override
    public void close() {
        try {
            client.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("closing the mediation HTTP client did not complete cleanly", e);
        }
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warn("closing the mediation Vert.x instance did not complete cleanly", e);
        }
    }
}

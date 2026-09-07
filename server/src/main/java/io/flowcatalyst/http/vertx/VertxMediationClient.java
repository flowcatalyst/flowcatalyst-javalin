package io.flowcatalyst.http.vertx;

import io.flowcatalyst.router.pool.MediationTransport;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
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
/// may not even be a Vert.x one (Javalin is still an option) — with a single
/// event loop, the same size [VertxListener] uses for the same reason:
/// mediation calls are I/O-bound on the loop and never run application code
/// there.
///
/// The composition root (`io.flowcatalyst.server.Router`) depends on this
/// class, never on `io.vertx.*` directly — `NoFrameworkLeakTest` forbids
/// that import anywhere outside this package.
public final class VertxMediationClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VertxMediationClient.class);

    /// The one number the ruling calls out explicitly; everything else is
    /// Vert.x's own default (pool size, keep-alive, pipelining) — no tuning
    /// knobs (`CLAUDE.md` "no tuning; defaults are the product").
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final Vertx vertx;
    private final HttpClient client;
    private final MediationTransport transport;

    private VertxMediationClient(Vertx vertx, HttpClient client, MediationTransport transport) {
        this.vertx = vertx;
        this.client = client;
        this.transport = transport;
    }

    public static VertxMediationClient start() {
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
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
        HttpClient client = vertx.createHttpClient(options);
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

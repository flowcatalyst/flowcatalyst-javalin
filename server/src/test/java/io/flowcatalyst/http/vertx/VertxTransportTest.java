package io.flowcatalyst.http.vertx;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.pool.HttpMediator;
import io.flowcatalyst.router.pool.HttpVersion;
import io.flowcatalyst.router.pool.JdkTransport;
import io.flowcatalyst.router.pool.MediationTransport;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router-h2.md` §5 (owner ruling 2026-09-07): [VertxTransport]
/// speaks h2c by *prior knowledge* to a cleartext target — no `Upgrade`
/// round trip, so it works on the bodied POST every mediation call actually
/// is, unlike `java.net.http.HttpClient` (`HttpMediatorVersionTest`'s
/// class doc records the empirical finding that motivated this class). It
/// never silently falls back to 1.1: a target that cannot speak h2 at all
/// fails the delivery.
class VertxTransportTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private Vertx serverVertx;
    private io.vertx.core.http.HttpServer h2cServer;
    private HttpServer legacyServer;
    private VertxMediationClient vertxClient;

    @AfterEach
    void stop() throws Exception {
        if (h2cServer != null) {
            h2cServer.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        if (serverVertx != null) {
            serverVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        if (legacyServer != null) {
            legacyServer.stop(0);
        }
        if (vertxClient != null) {
            vertxClient.close();
        }
    }

    @Test
    @DisplayName("deployed mode speaks h2c by prior knowledge to a cleartext target carrying a body")
    void deployedModeSpeaksH2cByPriorKnowledgeToACleartextTarget() throws Exception {
        var receivedBody = new AtomicReference<byte[]>();
        var receivedHeaders = new ConcurrentHashMap<String, String>();
        int port = startH2cServer(req -> req.bodyHandler(buf -> {
            receivedBody.set(buf.getBytes());
            req.headers().forEach(h -> receivedHeaders.put(h.getKey().toLowerCase(), h.getValue()));
            req.response().setStatusCode(200).end();
        }));

        var metrics = new PoolMetricsCollector(FIXED);
        vertxClient = VertxMediationClient.start();
        var mediator = mediator(vertxClient.transport(), metrics, Duration.ofSeconds(10));

        var outcome = mediator.deliver(message("msg_1", "http://127.0.0.1:" + port + "/hook"), true);

        assertThat(outcome).isEqualTo(MediationOutcome.Success.of(200));
        assertThat(new String(receivedBody.get(), StandardCharsets.UTF_8))
                .isEqualTo("{\"messageId\":\"msg_1\"}");
        assertThat(receivedHeaders).containsEntry("content-type", "application/json");
        assertThat(receivedHeaders).containsEntry("accept", "application/json");
        // Load-bearing: the only proof h2c actually happened — for a
        // CLEARTEXT target carrying a body, the exact case
        // `java.net.http.HttpClient` cannot do at all
        // (`docs/spec/router-h2.md` §5, finding Q7). Broken on purpose by
        // changing `setProtocolVersion(HttpVersion.HTTP_2)` to `HTTP_1_1`
        // in `VertxMediationClient.start()`: this assertion then fails
        // (0 recorded, not 1) — confirmed by hand, reverted. (Flipping
        // `setHttp2ClearTextUpgrade` to `true` instead does NOT kill this
        // particular test: this fixture's server has
        // `setHttp2ClearTextEnabled(true)`, i.e. it accepts the h2c
        // *upgrade* handshake too, and Vert.x's client — unlike the JDK's —
        // successfully upgrades a request carrying a body, so both paths
        // reach h2 here. Prior knowledge is still the production setting
        // because a target that only enables prior-knowledge h2c, not the
        // upgrade dance, needs it — Q7's whole point.)
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isEqualTo(1);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_1_1)).isEqualTo(0);
    }

    @Test
    @DisplayName("deployed mode does not silently downgrade against a target that cannot speak h2 at all")
    void deployedModeDoesNotDowngradeAgainstAnHttp1OnlyTarget() throws Exception {
        String baseUrl = startLegacyServer();

        var metrics = new PoolMetricsCollector(FIXED);
        vertxClient = VertxMediationClient.start();
        var mediator = mediator(vertxClient.transport(), metrics, Duration.ofSeconds(3));

        var outcome = mediator.deliver(message("msg_1", baseUrl), true);

        // A prior-knowledge h2 preface sent to a plain HTTP/1.1 server is
        // never a valid request as far as that server is concerned — the
        // delivery fails outright rather than being silently reinterpreted
        // as HTTP/1.1.
        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorConnection.class);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_1_1)).isZero();
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isZero();
    }

    @Test
    @DisplayName("dev mode (JdkTransport) still succeeds against the same HTTP/1.1-only target")
    void devModeStillSucceedsAgainstTheSameHttp1OnlyTarget() throws Exception {
        String baseUrl = startLegacyServer();

        var metrics = new PoolMetricsCollector(FIXED);
        var mediator = mediator(new JdkTransport(HttpMediator.defaultClient(true)), metrics, Duration.ofSeconds(10));

        var outcome = mediator.deliver(message("msg_1", baseUrl), true);

        assertThat(outcome).isEqualTo(MediationOutcome.Success.of(200));
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_1_1)).isEqualTo(1);
        assertThat(metrics.httpVersionCount(HttpVersion.HTTP_2)).isZero();
    }

    @Test
    @DisplayName("a target slower than the request timeout reports a timeout close to the deadline, not the sleep")
    void timeoutIsCloseToTheDeadlineNotTheTargetsSleep() throws Exception {
        Duration timeout = Duration.ofMillis(300);
        Duration serverSleep = Duration.ofSeconds(5);
        int port = startH2cServer(req -> req.bodyHandler(buf ->
                serverVertx.setTimer(serverSleep.toMillis(), id -> req.response().setStatusCode(200).end())));

        var metrics = new PoolMetricsCollector(FIXED);
        vertxClient = VertxMediationClient.start();
        var mediator = mediator(vertxClient.transport(), metrics, timeout);

        long startNanos = System.nanoTime();
        var outcome = mediator.deliver(message("msg_1", "http://127.0.0.1:" + port + "/hook"), true);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(outcome).isInstanceOf(MediationOutcome.ErrorConnection.class);
        assertThat(((MediationOutcome.ErrorConnection) outcome).message()).isEqualTo("request timeout");
        // Close to the 300 ms deadline the Vert.x event-loop timer owns,
        // nowhere near the target's 5 s sleep — proves the deadline fired
        // on the loop's own schedule rather than the caller having no
        // deadline at all (which would report success after ~5 s) and
        // rather than some other, unrelated bound.
        assertThat(elapsedMs).isLessThan(2000);
    }

    private static HttpMediator mediator(MediationTransport transport, PoolMetricsCollector metrics, Duration timeout) {
        var breakers = new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, FIXED);
        return new HttpMediator(transport, timeout, breakers, FIXED,
                (severity, category, text) -> { }, metrics);
    }

    private static Message message(String id, String target) {
        return new Message(id, "", null, null, MediationType.HTTP, target,
                null, false, DispatchMode.IMMEDIATE);
    }

    /// A Vert.x [io.vertx.core.http.HttpServer] with cleartext HTTP/2
    /// enabled, on its own [Vertx] instance (never the mediation client's).
    private int startH2cServer(io.vertx.core.Handler<io.vertx.core.http.HttpServerRequest> handler) throws Exception {
        serverVertx = Vertx.vertx();
        var options = new HttpServerOptions()
                .setHost("127.0.0.1")
                .setPort(0)
                .setHttp2ClearTextEnabled(true);
        h2cServer = serverVertx.createHttpServer(options).requestHandler(handler);
        h2cServer.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return h2cServer.actualPort();
    }

    /// The plain JDK [HttpServer] — HTTP/1.1 only, no h2 support of any
    /// kind — the same fixture `HttpMediatorTest`/`MediationConformanceTest`
    /// already use.
    private String startLegacyServer() throws Exception {
        legacyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        legacyServer.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        legacyServer.start();
        return "http://127.0.0.1:" + legacyServer.getAddress().getPort() + "/hook";
    }
}

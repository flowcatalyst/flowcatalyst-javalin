package io.flowcatalyst.fnhost.reconcile;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.function.EmitResult;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [HttpControlPlane] and [TokenSource] against real local [HttpServer]s —
/// no mocked `java.net.http.HttpClient`, same convention as
/// `OciArtifactStoreTest` (`docs/spec/function-host-reconciler.md` §1.1, R9).
class HttpControlPlaneTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(s -> s.stop(0));
    }

    private HttpServer startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(null);
        server.start();
        servers.add(server);
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void theTokenIsCachedAcrossCalls() throws Exception {
        AtomicInteger mints = new AtomicInteger();
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange -> {
            mints.incrementAndGet();
            respondJson(exchange, 200, "{\"access_token\":\"tok-" + mints.get() + "\",\"expires_in\":3600}");
        });
        server.createContext("/control/functions/desired-state", exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"etag\"");
            respondJson(exchange, 200, "{\"functions\":[],\"unload\":[]}");
        });

        HttpControlPlane cp = new HttpControlPlane(baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"));

        cp.desiredState(new DnsLabel("pool"), null);
        cp.desiredState(new DnsLabel("pool"), null);

        assertThat(mints.get()).as("mutant: refresh (mint) on every call instead of caching").isEqualTo(1);
    }

    @Test
    void a401RefreshesTheTokenOnceAndRetriesTheSameRequestOnce() throws Exception {
        AtomicInteger mints = new AtomicInteger();
        AtomicInteger desiredStateCalls = new AtomicInteger();
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange -> {
            mints.incrementAndGet();
            respondJson(exchange, 200, "{\"access_token\":\"tok-" + mints.get() + "\",\"expires_in\":3600}");
        });
        server.createContext("/control/functions/desired-state", exchange -> {
            int call = desiredStateCalls.incrementAndGet();
            if (call == 1) {
                respondJson(exchange, 401, "{}");
            } else {
                exchange.getResponseHeaders().add("ETag", "\"etag\"");
                respondJson(exchange, 200, "{\"functions\":[],\"unload\":[]}");
            }
        });

        HttpControlPlane cp = new HttpControlPlane(baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"));

        ControlPlane.Fetched fetched = cp.desiredState(new DnsLabel("pool"), null);

        assertThat(fetched).as("mutant: never retry after a 401").isInstanceOf(ControlPlane.Fetched.Changed.class);
        assertThat(desiredStateCalls.get()).as("mutant: retry more than once").isEqualTo(2);
        assertThat(mints.get()).as("a 401 must trigger exactly one refresh mint").isEqualTo(2);
    }

    @Test
    void aSecondConsecutive401SurfacesAsUnauthorized() throws Exception {
        AtomicInteger desiredStateCalls = new AtomicInteger();
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"unique-rejected-token-9k2f\",\"expires_in\":3600}"));
        server.createContext("/control/functions/desired-state", exchange -> {
            desiredStateCalls.incrementAndGet();
            respondJson(exchange, 401, "{}");
        });

        HttpControlPlane cp = new HttpControlPlane(baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"));

        assertThatThrownBy(() -> cp.desiredState(new DnsLabel("pool"), null))
                .isInstanceOf(ControlPlaneException.class)
                .satisfies(e -> assertThat(((ControlPlaneException) e).reason())
                        .isEqualTo(ControlPlaneException.Reason.UNAUTHORIZED))
                .as("mutant: put the (still-rejected) token in the exception message")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("unique-rejected-token-9k2f"));

        assertThat(desiredStateCalls.get())
                .as("mutant: retry more than once on a second consecutive 401 instead of surfacing UNAUTHORIZED")
                .isEqualTo(2);
    }

    @Test
    void heartbeatIsPostedWithTheBodyAndBearerToken() throws Exception {
        StringBuilder receivedBody = new StringBuilder();
        List<String> receivedAuth = new ArrayList<>();
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"heartbeat-tok\",\"expires_in\":3600}"));
        server.createContext("/control/functions/heartbeat", exchange -> {
            receivedAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
        });

        HttpControlPlane cp = new HttpControlPlane(baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"));

        cp.heartbeat(new HeartbeatReport("host-1", new DnsLabel("pool"), HeartbeatReport.HostState.ACTIVE, List.of()));

        assertThat(receivedAuth).containsExactly("Bearer heartbeat-tok");
        assertThat(receivedBody.toString()).contains("\"hostId\":\"host-1\"").contains("\"state\":\"ACTIVE\"");
    }

    // ── emit (spec function-context.md §3, D4c) ─────────────────────────────

    private static ControlPlane.EmitRequest emitRequest(String dedupId) {
        return new ControlPlane.EmitRequest("host-1", FunctionAddress.parse("a.svc.fn"), 1,
                List.of(new ControlPlane.EmitItem("app:sub:agg:evt", "subj-1", dedupId, "{}", null, null, null)));
    }

    @Test
    void emitRefreshesTheTokenOnceOn401AndRetriesTheSameRequestOnce() throws Exception {
        AtomicInteger mints = new AtomicInteger();
        AtomicInteger emitCalls = new AtomicInteger();
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange -> {
            mints.incrementAndGet();
            respondJson(exchange, 200, "{\"access_token\":\"tok-" + mints.get() + "\",\"expires_in\":3600}");
        });
        server.createContext("/control/functions/events", exchange -> {
            int call = emitCalls.incrementAndGet();
            if (call == 1) {
                respondJson(exchange, 401, "{}");
            } else {
                respondJson(exchange, 201, "{\"results\":[{\"id\":\"evt_1\",\"status\":\"SUCCESS\"}]}");
            }
        });

        HttpControlPlane cp = new HttpControlPlane(baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"));

        assertThat(cp.emit(emitRequest("dedup-refresh")))
                .as("mutant: the retried 2xx not read back, or the first 401 reported")
                .isEqualTo(new EmitResult.Emitted("evt_1"));
        assertThat(emitCalls.get()).as("mutant: retry more than once, or never retry after a 401").isEqualTo(2);
        assertThat(mints.get()).as("a 401 on emit must trigger exactly one refresh mint").isEqualTo(2);
    }

    @Test
    void aNon2xxEmitResponseIsARefusalCarryingItsCodeAndStatus() throws Exception {
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"tok\",\"expires_in\":3600}"));
        server.createContext("/control/functions/events", exchange ->
                respondJson(exchange, 403, "{\"error\":\"EVENT_TYPE_NOT_OWNED\",\"message\":\"nope\"}"));

        HttpControlPlane cp = new HttpControlPlane(baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"));

        EmitResult result = cp.emit(emitRequest("dedup-403"));
        assertThat(result).isInstanceOf(EmitResult.Refused.class);
        EmitResult.Refused refused = (EmitResult.Refused) result;
        assertThat(refused.code()).as("mutant: a fixed/wrong code instead of the platform's own").isEqualTo("EVENT_TYPE_NOT_OWNED");
        assertThat(refused.status()).as("mutant: a fixed status instead of the response's own").isEqualTo(403);
        assertThat(refused.message()).as("mutant: the platform's own reason dropped").contains("nope");
        assertThat(refused.retryable()).as("a 4xx refusal is not worth retrying").isFalse();
    }

    @Test
    void anAcceptedEmitWithAnUnreadableBodyIsStillEmitted() throws Exception {
        // Accepted is accepted: turning it into a refusal would invite a function to retry
        // an event the platform already stored.
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"tok\",\"expires_in\":3600}"));
        server.createContext("/control/functions/events", exchange -> respondJson(exchange, 201, "not json"));

        HttpControlPlane cp = new HttpControlPlane(baseUrl(server),
                new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", "secret-1"));

        assertThat(cp.emit(emitRequest("dedup-unreadable")))
                .as("mutant: an unreadable 2xx body reported as a refusal")
                .isEqualTo(new EmitResult.Emitted(""));
    }

    @Test
    void aTransportFailureOnEmitBecomesUnavailable503() throws Exception {
        // The token mint succeeds against a real server; the emit POST itself targets an
        // unreachable port, so this exercises sendEmit's own IOException branch specifically
        // (not TokenSource#token's — those are two different catch sites in HttpControlPlane#emit).
        HttpServer tokenServer = startServer();
        tokenServer.createContext("/oauth/token", exchange ->
                respondJson(exchange, 200, "{\"access_token\":\"tok\",\"expires_in\":3600}"));

        HttpControlPlane cp = new HttpControlPlane("http://localhost:1",
                new TokenSource(HttpClient.newHttpClient(), baseUrl(tokenServer), "client-1", "secret-1"));

        EmitResult result = cp.emit(emitRequest("dedup-unavailable"));
        assertThat(result).isInstanceOf(EmitResult.Refused.class);
        EmitResult.Refused refused = (EmitResult.Refused) result;
        assertThat(refused.code()).as("mutant: some other code for a transport failure").isEqualTo("UNAVAILABLE");
        assertThat(refused.status()).as("mutant: some other status for a transport failure").isEqualTo(503);
        assertThat(refused.retryable()).as("a transport failure is worth retrying").isTrue();
    }

    // ── R9: the secret and every minted token appear in no log line, no exception message ──

    @Test
    void theClientSecretAndTheMintedTokenNeverAppearInALogLineOrAnExceptionMessage() throws Exception {
        String secret = "super-secret-value-should-never-leak";
        AtomicInteger mints = new AtomicInteger();
        HttpServer server = startServer();
        server.createContext("/oauth/token", exchange -> {
            mints.incrementAndGet();
            respondJson(exchange, 200, "{\"access_token\":\"minted-token-must-not-leak-" + mints.get() + "\",\"expires_in\":3600}");
        });
        AtomicInteger desiredStateCalls = new AtomicInteger();
        server.createContext("/control/functions/desired-state", exchange -> {
            int call = desiredStateCalls.incrementAndGet();
            if (call == 1) {
                respondJson(exchange, 401, "{}"); // forces a refresh, exercising the debug log line
            } else {
                exchange.getResponseHeaders().add("ETag", "\"etag\"");
                respondJson(exchange, 200, "{\"functions\":[],\"unload\":[]}");
            }
        });

        TokenSource tokenSource = new TokenSource(HttpClient.newHttpClient(), baseUrl(server), "client-1", secret);
        HttpControlPlane cp = new HttpControlPlane(baseUrl(server), tokenSource);

        List<ILoggingEvent> events = captureLogs(TokenSource.class, HttpControlPlane.class, () -> {
            try {
                cp.desiredState(new DnsLabel("pool"), null);
            } catch (ControlPlaneException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(events).as("the scenario must actually log something for this test to mean anything").isNotEmpty();
        for (ILoggingEvent event : events) {
            String rendered = renderForLeakCheck(event);
            assertThat(rendered.toLowerCase(Locale.ROOT))
                    .as("mutant: log the secret").doesNotContain(secret.toLowerCase(Locale.ROOT));
            assertThat(rendered).as("mutant: log the minted token").doesNotContain("minted-token-must-not-leak");
        }

        // toString()/masking, and exception messages: also never the secret.
        assertThat(tokenSource.toString()).doesNotContain(secret);
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static List<ILoggingEvent> captureLogs(Class<?> a, Class<?> b, Runnable action) {
        ch.qos.logback.classic.Logger loggerA = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(a);
        ch.qos.logback.classic.Logger loggerB = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(b);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        var previousLevelA = loggerA.getLevel();
        var previousLevelB = loggerB.getLevel();
        loggerA.setLevel(ch.qos.logback.classic.Level.TRACE);
        loggerB.setLevel(ch.qos.logback.classic.Level.TRACE);
        loggerA.addAppender(appender);
        loggerB.addAppender(appender);
        try {
            action.run();
        } finally {
            loggerA.detachAppender(appender);
            loggerB.detachAppender(appender);
            loggerA.setLevel(previousLevelA);
            loggerB.setLevel(previousLevelB);
        }
        return List.copyOf(appender.list);
    }

    /// Every place a value could leak: the message, every structured key-value
    /// pair (`LOG.atDebug()...addKeyValue(...)`), and the cause's message/stacktrace.
    private static String renderForLeakCheck(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(event.getFormattedMessage());
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs().forEach(kv -> sb.append(' ').append(kv.key).append('=').append(kv.value));
        }
        var throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            sb.append(' ').append(throwableProxy.getMessage());
        }
        return sb.toString();
    }
}

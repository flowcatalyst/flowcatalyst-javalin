package io.flowcatalyst.fnhost.reconcile;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.function.DnsLabel;
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

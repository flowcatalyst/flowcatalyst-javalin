package io.flowcatalyst.mcp;

import io.flowcatalyst.platform.shared.json.Json;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Drives [VertxStreamableServerTransportProvider] directly with a raw
/// [HttpClient] (`docs/vertx-migration-brief.md` phase 3) — no MCP SDK client,
/// so the assertions pin the wire protocol the way [McpServerTest] pins the
/// SDK-client-observable behaviour. The provider under test is wired with
/// [McpServer#deriveSecurityValidator] — the same derived, listener-address-based
/// validator [McpServer#start] uses in production, not the provider's own
/// bare (empty-allow-list) default — so these tests exercise the real
/// Origin/Host rule end to end.
///
/// Mutants (run by hand, not by CI):
///   - drop `response.putHeader(HttpHeaders.MCP_SESSION_ID, sessionId)` in
///     `VertxStreamableServerTransportProvider#handleInitialize` →
///     [#initializeHandshakeReturnsASessionId] fails (no header).
///   - skip the `securityValidator.validateHeaders(headers)` call (or its
///     catch) in `handlePost`/`handleGet`/`handleDelete` →
///     [#aRequestWithABadOriginIsRefusedExactlyAsTheDefaultValidatorRefusesIt]
///     fails (200/other instead of 403).
///   - revert `@BeforeEach`'s `.securityValidator(...)` to the provider's bare
///     default (empty allow-lists) → [#anOriginMatchingTheListenersOwnAddressIsAccepted]
///     fails (403 instead of 200) — a legitimate same-machine `Origin` gets
///     refused exactly like `evil.example.com` because nothing is allow-listed.
class VertxStreamableServerTransportProviderTest {

    private Vertx vertx;
    private HttpServer httpServer;
    private McpSyncServer mcpServer;
    private VertxStreamableServerTransportProvider provider;
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        // Same reasoning as McpServer#start: the actual port is unknown until
        // listen() below succeeds, so deriveSecurityValidator reads it lazily.
        var boundPort = new AtomicInteger();
        provider = VertxStreamableServerTransportProvider.builder(vertx)
                .jsonMapper(new JacksonMcpJsonMapper((JsonMapper) Json.MAPPER))
                .securityValidator(McpServer.deriveSecurityValidator("127.0.0.1", boundPort::get))
                .build();
        var platform = new PlatformClient("http://127.0.0.1:1", new PlatformClient.AuthMode.None());
        mcpServer = io.modelcontextprotocol.server.McpServer.sync(provider)
                .serverInfo("test", "0.0")
                .tools(McpTools.all(platform))
                .build();

        Router router = Router.router(vertx);
        provider.mount(router);
        // h2c off — see McpServer#start's identical setting: HTTP/2 has no
        // literal Host header, so the derived validator's Host check would
        // refuse every request with 421 once the JDK HttpClient's default
        // HTTP_2 version upgrades the connection (Vert.x accepts h2c by default).
        httpServer = vertx.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(0)
                        .setHttp2ClearTextEnabled(false))
                .requestHandler(router);
        httpServer.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        port = httpServer.actualPort();
        boundPort.set(port);
    }

    @AfterEach
    void stop() throws Exception {
        try {
            mcpServer.closeGracefully();
        } catch (RuntimeException ignored) {
            // best-effort — a test that already broke the transport must still tear down
        }
        httpServer.shutdown(Duration.ofSeconds(5)).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private URI mcpUri() {
        return URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    private HttpRequest.Builder baseRequest() {
        return HttpRequest.newBuilder(mcpUri())
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
    }

    /// Initializes a session the way every real client does — the initialize
    /// request, then the `notifications/initialized` notification — and
    /// returns the session id from the `Mcp-Session-Id` response header.
    private String initializeSession() throws Exception {
        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                "capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""";
        var response = http.send(baseRequest().POST(HttpRequest.BodyPublishers.ofString(initBody)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("initialize response").isEqualTo(200);
        String sessionId = response.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new AssertionError("no Mcp-Session-Id header on the initialize response"));

        String initializedNotification = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""";
        http.send(baseRequest()
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(initializedNotification))
                .build(), HttpResponse.BodyHandlers.discarding());

        return sessionId;
    }

    private static String sseDataLine(String sseBody) {
        for (String line : sseBody.split("\n")) {
            if (line.startsWith("data: ")) return line.substring("data: ".length());
        }
        return null;
    }

    @Test
    void initializeHandshakeReturnsASessionId() throws Exception {
        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                "capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""";

        var response = http.send(baseRequest().POST(HttpRequest.BodyPublishers.ofString(initBody)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Mcp-Session-Id"))
                .as("Mcp-Session-Id response header")
                .isPresent();
    }

    @Test
    void toolsListOverTheSessionAnswersJson() throws Exception {
        String sessionId = initializeSession();

        String toolsListBody = """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""";
        var response = http.send(baseRequest()
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(toolsListBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String dataLine = sseDataLine(response.body());
        assertThat(dataLine).as("an SSE data line carrying the JSON-RPC response").isNotNull();

        var node = Json.MAPPER.readTree(dataLine);
        assertThat(node.path("result").path("tools").isArray())
                .as("tools/list result.tools is a JSON array")
                .isTrue();
        assertThat(node.path("result").path("tools").size())
                .as("the real tool catalogue is non-empty")
                .isGreaterThan(0);
    }

    @Test
    void getOpensSseStreamAndANotifyClientsBroadcastArrivesAsEvent() throws Exception {
        String sessionId = initializeSession();

        var getRequest = HttpRequest.newBuilder(mcpUri())
                .header("Accept", "text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .GET()
                .build();
        // Fired async: Vert.x defers flushing SSE headers until the first write,
        // so there is no externally observable "the listening stream is now
        // registered" signal to block on — the retry loop below is what makes
        // this test deterministic instead of a fixed sleep.
        var responseFuture = http.sendAsync(getRequest, HttpResponse.BodyHandlers.ofLines());

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<String> dataLineFuture = reader.submit(() -> {
                HttpResponse<Stream<String>> response = responseFuture.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200);
                Iterator<String> lines = response.body().iterator();
                while (lines.hasNext()) {
                    String line = lines.next();
                    if (line.startsWith("data: ")) return line.substring("data: ".length());
                }
                return null;
            });

            // A notifyClients call that beats the server-side listening-stream
            // registration is a safe, logged no-op (MissingMcpTransportSession) —
            // never an error — so retrying is correct, not just convenient.
            String dataLine = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (dataLine == null && System.nanoTime() < deadline) {
                provider.notifyClients("notifications/message", Map.of("data", "hello-from-test")).block();
                try {
                    dataLine = dataLineFuture.get(300, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                    // not yet delivered — retry
                }
            }

            assertThat(dataLine).as("an SSE data line for the broadcast notification").isNotNull();
            assertThat(dataLine).contains("notifications/message").contains("hello-from-test");
        } finally {
            reader.shutdownNow();
        }
    }

    @Test
    void deleteEndsTheSessionAndASubsequentPostWithThatIdIsRefused() throws Exception {
        String sessionId = initializeSession();

        var deleteResponse = http.send(HttpRequest.newBuilder(mcpUri())
                        .header("Mcp-Session-Id", sessionId)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(deleteResponse.statusCode()).isEqualTo(200);

        String pingBody = """
                {"jsonrpc":"2.0","id":3,"method":"ping"}""";
        var response = http.send(baseRequest()
                .header("Mcp-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(pingBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("a POST against a deleted session must be refused, not silently accepted")
                .isEqualTo(404);
    }

    @Test
    void aRequestWithABadOriginIsRefusedExactlyAsTheDefaultValidatorRefusesIt() throws Exception {
        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                "capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""";

        var response = http.send(baseRequest()
                .header("Origin", "http://evil.example.com")
                .POST(HttpRequest.BodyPublishers.ofString(initBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        // McpServer#deriveSecurityValidator's allow-list is this listener's own
        // addresses only (localhost/127.0.0.1/[::1]/bind host, this exact port);
        // an unrelated Origin is refused 403 — the same status
        // DefaultServerTransportSecurityValidator always uses for that.
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void anOriginMatchingTheListenersOwnAddressIsAccepted() throws Exception {
        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                "capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""";

        // A browser-based MCP client on this same machine, reached via
        // "localhost" rather than the literal "127.0.0.1" this test's requests
        // otherwise target — exactly the case McpServer#deriveSecurityValidator
        // exists to allow (docs/spec/mcp.md §1).
        var response = http.send(baseRequest()
                .header("Origin", "http://localhost:" + port)
                .POST(HttpRequest.BodyPublishers.ofString(initBody))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("an Origin matching the listener's own address, on its own bound port, must be accepted")
                .isEqualTo(200);
    }
}

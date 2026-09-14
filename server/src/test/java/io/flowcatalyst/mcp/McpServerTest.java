package io.flowcatalyst.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [McpServer] end to end (`docs/spec/mcp.md` §1/§5), over the Vert.x
/// transport ([VertxStreamableServerTransportProvider]): `GET /health` → 200,
/// a real streamable-HTTP session via the SDK's own client listing exactly
/// the 12 tools and 9 resources by name, the listener bound to the
/// configured host only, and [McpServer#deriveSecurityValidator]'s
/// Origin rule (a same-machine `Origin` accepted, an unrelated one refused).
class McpServerTest {

    private McpServer.Running running;

    @AfterEach
    void stopServer() {
        if (running != null) {
            running.stop();
        }
    }

    private PlatformClient platform() {
        // No tool is ever invoked in this test — only the catalogue's static
        // metadata is listed — so an unreachable, uncredentialed platform is fine.
        return new PlatformClient("http://127.0.0.1:1", new PlatformClient.AuthMode.None());
    }

    @Test
    void healthReturns200() throws Exception {
        running = McpServer.start(platform(), "127.0.0.1", 0, "test-version");

        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + "/health")).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void aRealStreamableHttpSessionListsExactlyTheTwelveToolsAndNineResourcesByName() {
        running = McpServer.start(platform(), "127.0.0.1", 0, "test-version");

        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + running.port())
                .endpoint("/mcp")
                .build();
        var client = McpClient.sync(transport).build();
        try {
            client.initialize();

            var toolNames = client.listTools().tools().stream().map(t -> t.name()).toList();
            var resourceNames = client.listResources().resources().stream().map(r -> r.name()).toList();
            var templateNames = client.listResourceTemplates().resourceTemplates().stream()
                    .map(t -> t.name()).toList();

            assertThat(toolNames).containsExactlyInAnyOrder(
                    "list_event_types", "get_event_type", "get_schema", "list_subscriptions", "get_subscription",
                    "list_applications", "list_roles", "get_role", "get_openapi", "whoami", "list_my_applications",
                    "get_application_capabilities");
            assertThat(resourceNames).containsExactlyInAnyOrder(
                    "Platform OpenAPI", "Applications", "Roles", "Event Types", "Subscriptions");
            assertThat(templateNames).containsExactlyInAnyOrder(
                    "Event Type", "Subscription", "Role", "Application");
            assertThat(resourceNames.size() + templateNames.size())
                    .as("five static resources + four templates = nine (docs/spec/mcp.md §4)")
                    .isEqualTo(9);
        } finally {
            client.closeGracefully();
        }
    }

    /// `McpServer#deriveSecurityValidator` end to end, through the real
    /// `start()` wiring (`docs/spec/mcp.md` §1): a browser-based MCP client on
    /// this same machine, reached via `localhost` on the listener's own
    /// (ephemeral, test-assigned) bound port, is accepted.
    @Test
    void anOriginMatchingTheListenersOwnAddressIsAccepted() throws Exception {
        running = McpServer.start(platform(), "127.0.0.1", 0, "test-version");

        var response = HttpClient.newHttpClient().send(initializeRequest(running.port())
                        .header("Origin", "http://localhost:" + running.port())
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    /// The other half of the same rule: an `Origin` unrelated to this
    /// listener's own address stays refused, exactly as
    /// `DefaultServerTransportSecurityValidator` refuses it.
    @Test
    void aRequestWithAnUnrelatedOriginIsRefused() throws Exception {
        running = McpServer.start(platform(), "127.0.0.1", 0, "test-version");

        var response = HttpClient.newHttpClient().send(initializeRequest(running.port())
                        .header("Origin", "http://evil.example.com")
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private static HttpRequest.Builder initializeRequest(int port) {
        String initBody = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
                "capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""";
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initBody));
    }

    /// Vert.x's [io.vertx.core.http.HttpServer] exposes no bound-address
    /// accessor to introspect directly (unlike Jetty's `ServerConnector`, which
    /// the pre-Vert.x version of this test read via `getHost()`), so this
    /// proves the configured host is actually occupied the behavioural way:
    /// a second listener bound to that exact `host:port` must conflict. If
    /// [McpServer#start]'s `host` parameter were ignored or misrouted (e.g.
    /// defaulting to `0.0.0.0` regardless of what was asked for), nothing
    /// would really be listening on `127.0.0.1:port` and this second bind
    /// would succeed instead of failing.
    @Test
    void theListenerIsBoundToTheConfiguredHostOnly() {
        running = McpServer.start(platform(), "127.0.0.1", 0, "test-version");

        Vertx probe = Vertx.vertx();
        try {
            var second = probe.createHttpServer(new HttpServerOptions().setHost("127.0.0.1").setPort(running.port()))
                    .requestHandler(req -> req.response().end());
            assertThatThrownBy(() -> second.listen().toCompletionStage().toCompletableFuture()
                    .get(5, TimeUnit.SECONDS))
                    .as("127.0.0.1:%d must already be occupied by the running MCP listener", running.port())
                    .isInstanceOf(ExecutionException.class);
        } finally {
            probe.close();
        }
    }
}

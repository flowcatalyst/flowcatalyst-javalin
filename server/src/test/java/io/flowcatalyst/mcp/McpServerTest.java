package io.flowcatalyst.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/// [McpServer] end to end (`docs/spec/mcp.md` §1/§5): `GET /health` → 200,
/// a real streamable-HTTP session via the SDK's own client listing exactly
/// the 12 tools and 9 resources by name, and the listener bound to the
/// configured host only.
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

    @Test
    void theListenerIsBoundToTheConfiguredHostOnly() {
        running = McpServer.start(platform(), "127.0.0.1", 0, "test-version");

        // Reach into the underlying Jetty connector rather than probing sockets:
        // exercises the same wiring McpServer#start uses (Javalin's JettyConfig),
        // without depending on this machine actually having another routable
        // interface to prove a negative against.
        var connector = (ServerConnector) jettyServerOf(running).getConnectors()[0];

        assertThat(connector.getHost()).isEqualTo("127.0.0.1");
    }

    private static org.eclipse.jetty.server.Server jettyServerOf(McpServer.Running running) {
        return running.app().jettyServer().server();
    }
}

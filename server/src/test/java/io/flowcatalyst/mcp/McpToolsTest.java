package io.flowcatalyst.mcp;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.json.Json;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/// [McpTools] against a stub platform (`docs/spec/mcp.md` §3/§5): every
/// tool's path and query, `get_schema`'s CURRENT→FINALISING fallback and its
/// "none" message, the capabilities bundle tolerating a 404, pretty JSON
/// (not Go-map-shaped), and a platform 500 becoming a tool error.
class McpToolsTest {

    private record StubResponse(int status, String body) {
    }

    private HttpServer server;
    private String baseUrl;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, StubResponse> responses = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var uri = exchange.getRequestURI();
            var pathAndQuery = uri.getQuery() == null ? uri.getPath() : uri.getPath() + "?" + uri.getQuery();
            requests.add(pathAndQuery);
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            var stub = responses.getOrDefault(pathAndQuery, new StubResponse(200, "{}"));
            var body = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private PlatformClient platform() {
        return new PlatformClient(baseUrl, new PlatformClient.AuthMode.Static("test-token"));
    }

    private McpSchema.CallToolResult call(String name, Map<String, Object> args) {
        var spec = McpTools.all(platform()).stream()
                .filter(t -> t.tool().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such tool: " + name));
        return spec.callHandler().apply(null, new McpSchema.CallToolRequest(name, args, null));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    // ── every platform call carries the bearer (spec §2) ─────────────────

    @Test
    void everyPlatformCallCarriesTheBearerToken() {
        call("whoami", Map.of());
        call("list_event_types", Map.of());
        assertThat(authorizationHeaders).hasSize(2).containsOnly("Bearer test-token");
    }

    // ── the catalogue itself ─────────────────────────────────────────────

    @Test
    void exposesExactlyTheTwelveNamedTools() {
        var names = McpTools.all(platform()).stream().map(t -> t.tool().name()).toList();

        assertThat(names).containsExactlyInAnyOrder(
                "list_event_types", "get_event_type", "get_schema", "list_subscriptions", "get_subscription",
                "list_applications", "list_roles", "get_role", "get_openapi", "whoami", "list_my_applications",
                "get_application_capabilities");
    }

    // ── list_event_types ─────────────────────────────────────────────────

    @Test
    void listEventTypesWithNoArgsHasNoQueryString() {
        call("list_event_types", Map.of());

        assertThat(requests).containsExactly("/api/event-types");
    }

    @Test
    void listEventTypesSendsEveryFilterAsAQueryParam() {
        call("list_event_types", Map.of(
                "status", "CURRENT", "application", "app1", "subdomain", "sub1",
                "aggregate", "agg1", "clientId", "c1"));

        assertThat(requests).hasSize(1);
        var req = requests.get(0);
        assertThat(req).startsWith("/api/event-types?")
                .contains("status=CURRENT").contains("application=app1").contains("subdomain=sub1")
                .contains("aggregate=agg1").contains("clientId=c1");
    }

    // ── get_event_type ───────────────────────────────────────────────────

    @Test
    void getEventTypeUsesTheIdInThePath() {
        call("get_event_type", Map.of("id", "evt_123"));

        assertThat(requests).containsExactly("/api/event-types/evt_123");
    }

    // ── get_schema ───────────────────────────────────────────────────────

    @Test
    void getSchemaFallsBackFromCurrentToFinalising() {
        responses.put("/api/event-types/evt_1", new StubResponse(200,
                "{\"specVersions\":[{\"status\":\"FINALISING\",\"schema\":{\"type\":\"object\",\"title\":\"X\"}}]}"));

        var result = call("get_schema", Map.of("id", "evt_1"));

        assertThat(result.isError()).as("a resolved schema is a normal result").isNotEqualTo(Boolean.TRUE);
        var node = Json.MAPPER.readTree(text(result));
        assertThat(node.get("title").asString()).isEqualTo("X");
    }

    @Test
    void getSchemaWithNoMatchingVersionReturnsANonErrorMessageNamingTheRequestedStatus() {
        responses.put("/api/event-types/evt_2", new StubResponse(200, "{\"specVersions\":[]}"));

        var result = call("get_schema", Map.of("id", "evt_2"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo("no CURRENT spec version");
    }

    @Test
    void getSchemaHonoursAnExplicitVersionArgument() {
        responses.put("/api/event-types/evt_3", new StubResponse(200,
                "{\"specVersions\":[{\"status\":\"DEPRECATED\",\"schema\":{\"type\":\"object\"}}]}"));

        var result = call("get_schema", Map.of("id", "evt_3", "version", "DEPRECATED"));

        assertThat(Json.MAPPER.readTree(text(result)).get("type").asString()).isEqualTo("object");
    }

    /// The CURRENT→FINALISING fallback must not fire for any other requested
    /// status — a mutant that always falls back to FINALISING regardless of
    /// `want` would still pass the FINALISING-fallback test above but fails
    /// this one.
    @Test
    void getSchemaDoesNotFallBackForANonCurrentRequest() {
        responses.put("/api/event-types/evt_4", new StubResponse(200,
                "{\"specVersions\":[{\"status\":\"FINALISING\",\"schema\":{\"type\":\"object\"}}]}"));

        var result = call("get_schema", Map.of("id", "evt_4", "version", "DEPRECATED"));

        assertThat(text(result)).isEqualTo("no DEPRECATED spec version");
    }

    // ── list_subscriptions / get_subscription ───────────────────────────

    @Test
    void listSubscriptionsWithNoClientIdHasNoQueryString() {
        call("list_subscriptions", Map.of());

        assertThat(requests).containsExactly("/api/subscriptions");
    }

    @Test
    void listSubscriptionsWithAnAdminClientIdSendsIt() {
        call("list_subscriptions", Map.of("clientId", "client-1"));

        assertThat(requests).containsExactly("/api/subscriptions?clientId=client-1");
    }

    @Test
    void getSubscriptionUsesTheIdInThePath() {
        call("get_subscription", Map.of("id", "sub_1"));

        assertThat(requests).containsExactly("/api/subscriptions/sub_1");
    }

    // ── list_applications ────────────────────────────────────────────────

    @Test
    void listApplicationsDefaultsToActiveTrue() {
        call("list_applications", Map.of());

        assertThat(requests).containsExactly("/api/applications?active=true");
    }

    @Test
    void listApplicationsHonoursExplicitActiveFalse() {
        call("list_applications", Map.of("active", false));

        assertThat(requests).containsExactly("/api/applications?active=false");
    }

    // ── list_roles / get_role ────────────────────────────────────────────

    @Test
    void listRolesWithNoSourceHasNoQueryString() {
        call("list_roles", Map.of());

        assertThat(requests).containsExactly("/api/roles");
    }

    @Test
    void listRolesHonoursTheSourceFilter() {
        call("list_roles", Map.of("source", "CODE"));

        assertThat(requests).containsExactly("/api/roles?source=CODE");
    }

    @Test
    void getRoleUsesTheIdInThePath() {
        call("get_role", Map.of("id", "role_1"));

        assertThat(requests).containsExactly("/api/roles/role_1");
    }

    // ── get_openapi ──────────────────────────────────────────────────────

    @Test
    void getOpenApiDefaultsToThePlatformApplicationAndResolvesTwoHop() {
        responses.put("/api/applications/by-code/platform", new StubResponse(200, "{\"id\":\"app_1\"}"));
        responses.put("/bff/developer/applications/app_1/openapi/current", new StubResponse(200, "{\"openapi\":\"3.0.0\"}"));

        var result = call("get_openapi", Map.of());

        assertThat(requests).containsExactly(
                "/api/applications/by-code/platform", "/bff/developer/applications/app_1/openapi/current");
        assertThat(Json.MAPPER.readTree(text(result)).get("openapi").asString()).isEqualTo("3.0.0");
    }

    @Test
    void getOpenApiHonoursAnExplicitApplicationCode() {
        responses.put("/api/applications/by-code/myapp", new StubResponse(200, "{\"id\":\"app_2\"}"));
        responses.put("/bff/developer/applications/app_2/openapi/current", new StubResponse(200, "{\"openapi\":\"3.1.0\"}"));

        call("get_openapi", Map.of("applicationCode", "myapp"));

        assertThat(requests).containsExactly(
                "/api/applications/by-code/myapp", "/bff/developer/applications/app_2/openapi/current");
    }

    // ── whoami / list_my_applications ────────────────────────────────────

    @Test
    void whoamiCallsMe() {
        call("whoami", Map.of());

        assertThat(requests).containsExactly("/api/me");
    }

    @Test
    void listMyApplicationsCallsMeApplications() {
        call("list_my_applications", Map.of());

        assertThat(requests).containsExactly("/api/me/applications");
    }

    // ── get_application_capabilities ────────────────────────────────────

    @Test
    void getApplicationCapabilitiesBundlesEverySubResourceTolerating404OnRoles() {
        responses.put("/api/applications/by-code/myapp", new StubResponse(200, "{\"id\":\"app_9\",\"name\":\"MyApp\"}"));
        responses.put("/bff/developer/applications/app_9/openapi/current", new StubResponse(200, "{\"openapi\":\"3.0.0\"}"));
        responses.put("/api/roles/by-application/app_9", new StubResponse(404, "{\"error\":\"NOT_FOUND\"}"));
        responses.put("/api/event-types?application=myapp&status=CURRENT", new StubResponse(200, "{\"items\":[]}"));

        var result = call("get_application_capabilities", Map.of("applicationCode", "myapp"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        var node = Json.MAPPER.readTree(text(result));
        assertThat(node.get("application").get("name").asString()).isEqualTo("MyApp");
        assertThat(node.get("openapi").get("openapi").asString()).isEqualTo("3.0.0");
        assertThat(node.get("assignableRoles").isNull())
                .as("a 404 sub-resource degrades to null, not a failure").isTrue();
        assertThat(node.get("eventTypes").isNull()).isFalse();
    }

    /// [PlatformClient#getTolerating404] must tolerate *only* 404 — any other
    /// sub-resource failure still fails the whole bundle. A mutant that
    /// swallows every error (not just 404) would still pass the test above
    /// but fails this one.
    @Test
    void getApplicationCapabilitiesPropagatesANon404SubResourceFailure() {
        responses.put("/api/applications/by-code/myapp", new StubResponse(200, "{\"id\":\"app_9\"}"));
        responses.put("/bff/developer/applications/app_9/openapi/current", new StubResponse(500, "{\"message\":\"boom\"}"));

        var result = call("get_application_capabilities", Map.of("applicationCode", "myapp"));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
    }

    // ── cross-cutting: JSON shape, errors, credentials ──────────────────

    @Test
    void toolResultIsPrettyPrintedJsonNotAGoStyleMap() {
        responses.put("/api/event-types", new StubResponse(200, "{\"items\":[{\"code\":\"order.created\",\"status\":\"CURRENT\"}]}"));

        var text = text(call("list_event_types", Map.of()));

        assertThat(Json.MAPPER.readTree(text)).isNotNull();
        assertThat(text).doesNotContain("map[");
        assertThat(text).contains("order.created").contains("\n");
    }

    @Test
    void aPlatform500BecomesATooLErrorCarryingTheEnvelopeText() {
        responses.put("/api/me", new StubResponse(500, "{\"message\":\"internal boom\",\"error\":\"INTERNAL\"}"));

        var result = call("whoami", Map.of());

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).contains("INTERNAL").contains("internal boom");
    }

    @Test
    void aToolWithNoCredentialsConfiguredAnswersTheMissingCredentialsErrorWithoutCallingThePlatform() {
        var noAuthPlatform = new PlatformClient(baseUrl, new PlatformClient.AuthMode.None());
        var spec = McpTools.all(noAuthPlatform).stream()
                .filter(t -> t.tool().name().equals("whoami"))
                .findFirst().orElseThrow();

        var result = spec.callHandler().apply(null, new McpSchema.CallToolRequest("whoami", Map.of(), null));

        assertThat(result.isError()).isEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo(McpConfig.MISSING_CREDENTIALS_MESSAGE);
        assertThat(requests).as("no HTTP call was made at all").isEmpty();
    }
}

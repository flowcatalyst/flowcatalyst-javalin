package io.flowcatalyst.server;

import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Boots the real [Server] (platform enabled, ephemeral ports) against the
/// migrated embedded Postgres and checks the listener surface Go exposes:
/// `/health` on both ports, `/ready`, `/metrics`, the spec routes, the SPA
/// fallback, and the error envelope for an unknown API route.
class ServerTest {

    private static Server.Running running;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_HTTP", System.getProperty("fc.http", System.getenv().getOrDefault("FC_HTTP", "javalin")),
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true"));
        running = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Frontend.embeddedOrNone(), new PrometheusRegistry()).start();
    }

    @AfterAll
    static void stop() {
        running.stop();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthOnBothListeners() throws Exception {
        // The API listener carries the readiness checks (ruling C-Q23): the
        // login-attempt partitions the backoff store needs are present here
        // because the migrations and the purger create them.
        var api = get(running.apiPort(), "/health");
        assertThat(api.statusCode()).as(api.body()).isEqualTo(200);
        assertThat(api.body()).isEqualTo("{\"status\":\"UP\",\"version\":\"dev\",\"checks\":{\"loginAttemptPartitions\":\"ok\"}}\n");
        // The metrics listener has no checks to run.
        var metrics = get(running.metricsPort(), "/health");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).isEqualTo("{\"status\":\"UP\",\"version\":\"dev\"}\n");
    }

    @Test
    void readyListsEverySubsystemToggleInGoOrder() throws Exception {
        var r = get(running.metricsPort(), "/ready");
        assertThat(r.body()).isEqualTo(
                "{\"mcp\":false,\"outbox\":false,\"platform\":true,\"router\":false,\"scheduled_job\":false,\"scheduler\":false,\"status\":\"ready\",\"stream\":false}\n");
    }

    @Test
    void metricsIsARealPrometheusEndpoint() throws Exception {
        var r = get(running.metricsPort(), "/metrics");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
    }

    @Test
    void specAndSpaAreServedFromTheApiListener() throws Exception {
        assertThat(get(running.apiPort(), "/api/openapi.json").statusCode()).isEqualTo(200);
        assertThat(get(running.apiPort(), "/q/openapi").statusCode()).isEqualTo(200);
        var spa = get(running.apiPort(), "/event-types");
        assertThat(spa.statusCode()).isEqualTo(200);
        assertThat(spa.body()).contains("<div id=\"app\">");
    }

    @Test
    void unknownApiRouteGetsTheEnvelopeNotTheSpa() throws Exception {
        // Only GET falls through to the SPA; a POST to an unknown path is an API 404 envelope.
        var r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/api/nope"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).startsWith("{\"error\":\"NOT_FOUND\"");
    }

    /// Dispatch-seam spec §7 audit item 1: `Platform.register()` used to
    /// start a `DispatchJobReaper` and discard the handle, so nothing ever
    /// stopped it — every test that built a `Platform`/`Server` leaked a
    /// background thread that swept the shared test database. This boots and
    /// stops its own [Server] (independent of the shared `running` instance,
    /// since `stop()` only runs once for that one in `@AfterAll`) and asserts
    /// the reaper's executor is actually shut down afterward — a state that
    /// must change, not merely the absence of a symptom.
    @Test
    void stopClosesTheDispatchJobReaper() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_HTTP", System.getProperty("fc.http", System.getenv().getOrDefault("FC_HTTP", "javalin")),
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true"));
        var server = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Frontend.embeddedOrNone(), new PrometheusRegistry());
        var oneOff = server.start();
        try {
            assertThat(oneOff.dispatchJobReaperClosed()).isFalse();
        } finally {
            oneOff.stop();
        }
        assertThat(oneOff.dispatchJobReaperClosed()).isTrue();
    }
}

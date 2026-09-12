package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.config.RouterConfig;
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

/// R3 (`docs/spec/deployed-dispatch.md` §3): `/api/dispatch/router-config`
/// is served on the internal listener only, never the public one, and the
/// bytes it serves are real input to the router's own config parser — not
/// merely a plausible-looking string.
class RouterConfigEndpointTest {

    private static Server.Running running;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        Env env = Env.load(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true",
                "FC_DISPATCH_QUEUE_TYPE", "SQS",
                "FC_DISPATCH_QUEUE_PREFIX", "FC-endpointtest",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/fc-dispatch",
                "FC_AUTH_ALLOW_TEST_HEADERS", "true"));
        // Spa.none(), deliberately, not the embedded SPA: with a real SPA
        // mounted, an unmatched GET on the API listener falls through to the
        // SPA's index.html (200), the same as any other unknown path
        // (ServerTest#specAndSpaAreServedFromTheApiListener) — that would
        // mask exactly the absence this test wants to prove.
        running = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Server.Spa.none(),
                new PrometheusRegistry()).start();
    }

    @AfterAll
    static void stop() {
        running.stop();
    }

    private static HttpResponse<byte[]> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /// Pins R3's whole point: the document is reachable on the internal
    /// listener, and — the part that actually proves the contract, not just
    /// a string comparison — the served bytes parse through the exact
    /// [RouterConfig] deserialisation path
    /// [io.flowcatalyst.router.config.http.HttpConfigSource] uses
    /// (`Json.MAPPER.readValue(bytes, RouterConfig.class)`), and the
    /// always-present `platform` `DEFAULT` queue survives that round trip
    /// with its composed name intact. A mutant that serialised some other
    /// shape, or broke the queue-name composition, would fail this
    /// assertion even though a bare "200 OK" check would not have caught it.
    @Test
    void theServedDocumentRoundTripsThroughTheRoutersOwnParser() throws Exception {
        var response = get(running.metricsPort(), "/api/dispatch/router-config");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");

        RouterConfig config = Json.MAPPER.readValue(response.body(), RouterConfig.class);

        assertThat(config.queues())
                .anyMatch(q -> q.queueName().equals("FC-endpointtest-platform-DEFAULT.fifo")
                        && q.queueUri().equals("https://sqs.us-east-1.amazonaws.com/123456789012/FC-endpointtest-platform-DEFAULT.fifo"));
    }

    /// Pins R3's other half: the document is absent from the public API
    /// listener — the whole reason it moved to the internal one is that 8080
    /// is ALB-facing and the document lists client identifiers, pool codes
    /// and queue URLs. A mutant that registered the route on both listeners
    /// (or moved it back to the API one) would turn this 404 into a 200.
    @Test
    void theRouteIsAbsentFromTheApiListener() throws Exception {
        var response = get(running.apiPort(), "/api/dispatch/router-config");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    /// A worker/router-only instance is never given a
    /// [io.flowcatalyst.platform.dispatch.RouterConfigDocumentBuilder], so
    /// [Metrics] never registers the route there. Pinned directly against
    /// [Server#dispatchRouterConfigFor] rather than over HTTP: `Metrics`
    /// installs no `HttpError` 404 envelope of its own (only
    /// `Platform.register` does — a pre-existing gap, not one introduced by
    /// this unit), so an unmatched route there is a bare Javalin 500 exactly
    /// like a route that exists but throws, meaning no HTTP status code can
    /// tell "correctly never registered" apart from "wrongly registered with
    /// a null builder". The mode gate itself is what must be right, so that
    /// is what this test checks.
    @Test
    void dispatchRouterConfigIsOnlyBuiltInPlatformMode() {
        Env env = Env.load(Map.of("FC_DATABASE_URL", "postgresql://u@h:5432/db"));

        assertThat(Server.dispatchRouterConfigFor(new Server.Mode.Platform(TestPg.dataSource()), env)).isNotNull();
        assertThat(Server.dispatchRouterConfigFor(Server.Mode.routerOnly(), env)).isNull();
        assertThat(Server.dispatchRouterConfigFor(new Server.Mode.Worker(TestPg.dataSource()), env)).isNull();
    }
}

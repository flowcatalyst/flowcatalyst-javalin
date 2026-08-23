package io.flowcatalyst.platform.publicapi.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.publicapi.Branding;
import io.flowcatalyst.platform.publicapi.BrandingFixture;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.server.Env;
import io.flowcatalyst.server.Server;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The public routes end to end (spec §2–3): the payloads, the
/// defaults with no configuration, the verbatim echo of a stored theme — and,
/// through the real `Server` with its authenticator wired, that an anonymous
/// request (no bearer, no cookie, no test headers) still gets `200`.
class PublicApiTest {

    private static TestHttp http;

    @BeforeAll
    static void start() {
        BrandingFixture.clearAll();
        var branding = new Branding(BrandingFixture.REPO);
        http = new TestHttp(cfg -> {
            HttpError.install(cfg.routes);
            PublicApi.register(cfg.routes, new PublicApi.State(branding));
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        BrandingFixture.clearAll();
    }

    @AfterEach
    void clearRows() {
        BrandingFixture.clearAll();
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    // ── GET /api/public/platform ───────────────────────────────────────────

    @Test
    void platformReturnsTheFeatureFlagsAndTheDefaultName() {
        var r = http.get("/api/public/platform");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        var body = json(r);
        assertThat(body.path("features").path("messagingEnabled").asBoolean()).isTrue();
        assertThat(body.path("platformName").asText()).isEqualTo("Flowcatalyst");
        assertThat(body.fieldNames()).toIterable().containsExactly("features", "platformName");
    }

    @Test
    void platformReturnsTheConfiguredNameTrimmed() {
        BrandingFixture.set(Branding.PLATFORM_NAME, "  Acme  ");

        assertThat(json(http.get("/api/public/platform")).path("platformName").asText()).isEqualTo("Acme");
    }

    @Test
    void legacyConfigPathServesTheSamePlatformDocument() {
        BrandingFixture.set(Branding.PLATFORM_NAME, "Acme");

        var legacy = json(http.get("/api/config/platform"));
        assertThat(legacy).as("spec §9 Q1: same handler, same body").isEqualTo(json(http.get("/api/public/platform")));
        assertThat(legacy.path("platformName").asText()).isEqualTo("Acme");
    }

    // ── GET /api/public/login-theme ────────────────────────────────────────

    @Test
    void loginThemeIsAnEmptyObjectWhenNothingIsConfigured() {
        var r = http.get("/api/public/login-theme");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r).isObject()).isTrue();
        assertThat(json(r).size()).as("no field is invented server-side; the SPA holds the defaults").isZero();
    }

    @Test
    void loginThemeEchoesTheStoredDocumentAndOmitsAbsentFields() {
        BrandingFixture.set(Branding.LOGIN_THEME, """
                {"brandName":"Acme","brandSubtitle":"","logoHeight":48,"primaryColor":"red","customCss":".a{}","unknown":1}
                """);

        var body = json(http.get("/api/public/login-theme"));
        assertThat(body.path("brandName").asText()).isEqualTo("Acme");
        assertThat(body.path("brandSubtitle").asText()).as("stored empty string is kept").isEmpty();
        assertThat(body.path("logoHeight").asInt()).isEqualTo(48);
        assertThat(body.path("primaryColor").asText()).as("echoed, not validated").isEqualTo("red");
        assertThat(body.path("customCss").asText()).isEqualTo(".a{}");
        assertThat(body.has("logoUrl")).isFalse();
        assertThat(body.has("footerText")).isFalse();
        assertThat(body.has("unknown")).isFalse();
    }

    @Test
    void loginThemeIsEmptyWhenTheStoredValueIsMalformed() {
        BrandingFixture.set(Branding.LOGIN_THEME, "{not json");

        var r = http.get("/api/public/login-theme");
        assertThat(r.statusCode()).as("always 200 + an object").isEqualTo(200);
        assertThat(json(r).size()).isZero();
    }

    @Test
    void loginThemeIgnoresTheClientIdQueryParameter() {
        BrandingFixture.set(Branding.LOGIN_THEME, "{\"brandName\":\"Acme\"}");

        assertThat(json(http.get("/api/public/login-theme?clientId=cli_whatever")).path("brandName").asText()).isEqualTo("Acme");
    }

    // ── Outside the authenticator (spec §1, §7) ────────────────────────────

    @Test
    void anonymousRequestsSucceedThroughTheRealServerMiddleware() throws Exception {
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true"));
        var running = new Server(env, new Server.Mode.Platform(TestPg.dataSource()), Server.Spa.none(), new PrometheusRegistry()).start();
        try {
            var client = HttpClient.newHttpClient();
            for (String path : List.of("/api/public/platform", "/api/config/platform", "/api/public/login-theme")) {
                var r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(r.statusCode()).as("anonymous GET %s", path).isEqualTo(200);
                assertThat(json(r).isObject()).isTrue();
            }
            // …while the neighbouring platform surface still refuses the same anonymous caller (403 UNAUTHENTICATED).
            var guarded = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + running.apiPort() + "/api/event-types")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(guarded.statusCode()).as("the guarded surface is not public").isEqualTo(403);
            assertThat(json(guarded).path("error").asText()).isEqualTo("UNAUTHENTICATED");
        } finally {
            running.stop();
        }
    }
}

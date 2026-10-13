package io.flowcatalyst.server;

import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The CORS filter through the REAL composed platform (`docs/spec/cors.md` §9.3, the
/// 2026-10-14 follow-up to the F16 mirror): a real `/auth/login` session cookie
/// (`__Host-fc_session`), a real allow-listed origin added through the API, the real
/// authenticator and routes.
///
/// The finding: an allow-listed origin used to get `Access-Control-Allow-Credentials: true`,
/// so a page on it (a same-site sibling, which `SameSite=Lax` does not stop) could
/// `fetch(..., {credentials: "include"})` and **read** what the user's session cookie
/// authenticated. `SessionCsrf` refuses its writes; this pins that reads are not granted
/// either: the browser only exposes a credentialed cross-origin response that carries
/// `Access-Control-Allow-Credentials: true`, so its absence is the whole control. Bearer
/// callers, which need no credentials flag, keep the allow-list.
class CorsCredentialsIntegrationTest {

    private static final String PUBLIC_BASE = "https://platform.example.com";
    private static final String ADMIN_EMAIL = "cors-admin@example.com";
    private static final String PASSWORD = "Cors-Passw0rd-long-enough!";
    /// Allow-listed, and a same-site sibling of the platform: the case Lax cookies reach.
    private static final String LISTED = "https://app.example.com";
    private static final String NOT_LISTED = "https://evil.example.com";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static Server.Running running;
    private static int port;
    private static String cookie;
    private static String bearer;

    @BeforeAll
    static void start() throws Exception {
        DataSource ds = TestPg.newDatabase("cors_credentials_test");
        Migrator.migrate(ds);
        new Seeder(ds, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_EMAIL, ADMIN_EMAIL,
                Seeder.ENV_BOOTSTRAP_PASSWORD, PASSWORD))).run();
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true",
                "FC_JWT_ISSUER", PUBLIC_BASE, "FLOWCATALYST_APP_KEY", Encryption.generateKey()));
        running = new Server(env, new Server.Mode.Platform(Pools.ofSingle(ds)), Frontend.embeddedOrNone(),
                new PrometheusRegistry()).start();
        port = running.apiPort();

        var login = send("POST", "/auth/login", "{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}",
                "Content-Type", "application/json");
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        String sc = login.headers().allValues("set-cookie").stream().filter(c -> c.contains("fc_session=")).findFirst().orElseThrow();
        cookie = sc.substring(0, sc.indexOf(';'));
        assertThat(cookie).startsWith("__Host-fc_session=");

        // The allow-list entry, added the way an operator does (the SPA, same-origin).
        assertThat(send("POST", "/api/platform/cors", "{\"origin\":\"" + LISTED + "\"}", sameOrigin()).statusCode()).isEqualTo(201);

        // A bearer for the same authority: a provisioned service account given super-admin.
        var app = json(send("POST", "/api/applications", "{\"code\":\"corsapp\",\"name\":\"corsapp\"}", sameOrigin()), 201);
        var provisioned = json(send("POST", "/api/applications/" + app.get("id").asString() + "/provision-service-account", null,
                "Cookie", cookie, "Sec-Fetch-Site", "same-origin"), 201);
        var oauth = provisioned.get("serviceAccount").get("oauthClient");
        var sa = json(send("GET", "/api/service-accounts/code/app:corsapp", null, "Cookie", cookie), 200);
        assertThat(send("PUT", "/api/service-accounts/" + sa.get("id").asString() + "/roles",
                "{\"roles\":[\"platform:super-admin\"]}", sameOrigin()).statusCode()).isEqualTo(200);
        var token = send("POST", "/oauth/token", "grant_type=client_credentials&client_id="
                        + URLEncoder.encode(oauth.get("clientId").asString(), StandardCharsets.UTF_8)
                        + "&client_secret=" + URLEncoder.encode(oauth.get("clientSecret").asString(), StandardCharsets.UTF_8),
                "Content-Type", "application/x-www-form-urlencoded");
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        bearer = Json.MAPPER.readTree(token.body()).get("access_token").asString();
    }

    @AfterAll
    static void stop() {
        if (running != null) running.stop();
    }

    private static String[] sameOrigin() {
        return new String[] {"Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Content-Type", "application/json"};
    }

    private static HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return Json.MAPPER.readTree(r.body());
    }

    private static void assertVariesOnOrigin(HttpResponse<String> r) {
        assertThat(r.headers().allValues("Vary")).anyMatch(v -> v.contains("Origin"));
    }

    /// What `fetch("https://platform.example.com/api/clients", {credentials: "include"})` on
    /// the allow-listed sibling sends: the session cookie, the page's `Origin`, `same-site`.
    /// The server still answers (CORS is a browser control), but without
    /// `Access-Control-Allow-Credentials` the browser does not hand the body to the page.
    @Test
    void aCookieAuthenticatedReadFromAnAllowListedOriginIsNotExposedToIt() throws Exception {
        var r = send("GET", "/api/clients", null, "Cookie", cookie, "Origin", LISTED,
                "Sec-Fetch-Site", "same-site", "Sec-Fetch-Mode", "cors");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).contains(LISTED);
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials"))
                .as("a credentialed cross-origin read must not be granted")
                .isEmpty();
        assertVariesOnOrigin(r);
    }

    /// The preflight a credentialed non-simple request needs: answered for the listed origin
    /// (bearer callers rely on it) but without the credentials grant, so the browser refuses
    /// to send the credentialed request at all.
    @Test
    void thePreflightForAnAllowListedOriginGrantsNoCredentials() throws Exception {
        var r = send("OPTIONS", "/api/clients", null, "Origin", LISTED,
                "Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "content-type");

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).contains(LISTED);
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertVariesOnOrigin(r);
    }

    /// The legitimate use kept: a browser page on a listed origin calling with its own bearer
    /// token (no cookie, no credentials mode) gets its preflight and a readable response.
    @Test
    void aBearerCallerOnAnAllowListedOriginKeepsTheAllowList() throws Exception {
        var pre = send("OPTIONS", "/api/clients", null, "Origin", LISTED,
                "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "authorization");
        assertThat(pre.statusCode()).isEqualTo(204);
        assertThat(pre.headers().firstValue("Access-Control-Allow-Origin")).contains(LISTED);
        assertThat(pre.headers().firstValue("Access-Control-Allow-Headers")).contains("authorization");

        var r = send("GET", "/api/clients", null, "Authorization", "Bearer " + bearer, "Origin", LISTED,
                "Sec-Fetch-Site", "same-site", "Sec-Fetch-Mode", "cors");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).contains(LISTED);
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertVariesOnOrigin(r);
    }

    @Test
    void aNotListedOriginIsNeitherReflectedNorCredentialed() throws Exception {
        var r = send("GET", "/api/clients", null, "Cookie", cookie, "Origin", NOT_LISTED,
                "Sec-Fetch-Site", "same-site", "Sec-Fetch-Mode", "cors");
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertVariesOnOrigin(r);

        var pre = send("OPTIONS", "/api/clients", null, "Origin", NOT_LISTED, "Access-Control-Request-Method", "GET");
        assertThat(pre.statusCode()).isEqualTo(403);
        assertThat(pre.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(pre.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    /// The platform's own SPA is same-origin: no `Origin` on its GETs (or its own on writes),
    /// never a CORS grant, and its cookie reads are untouched.
    @Test
    void theSameOriginSpaIsUnaffected() throws Exception {
        var r = send("GET", "/api/clients", null, "Cookie", cookie, "Sec-Fetch-Site", "same-origin");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(r.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertVariesOnOrigin(r);
    }
}

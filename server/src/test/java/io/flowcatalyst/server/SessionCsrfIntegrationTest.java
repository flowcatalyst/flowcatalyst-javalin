package io.flowcatalyst.server;

import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static org.assertj.core.api.Assertions.assertThat;

/// The cookie-session guard (`SessionCsrf`, Go F16 finding 1) through the REAL
/// composed platform: a real `/auth/login` session cookie (`__Host-fc_session`,
/// the deployed name), the real authenticator, real routes and the database.
/// Go's `internal/server/crossorigin_pg_test.go`. Each refusal also asserts
/// that nothing was written.
class SessionCsrfIntegrationTest {

    private static final String PUBLIC_BASE = "https://platform.example.com";
    private static final String ADMIN_EMAIL = "csrf-admin@example.com";
    private static final String PASSWORD = "Csrf-Passw0rd-long-enough!";
    private static final String SIBLING = "https://evil.example.com";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static Server.Running running;
    private static int port;
    private static String cookie;
    private static String roleLessCookie;
    private static String bearer;
    private static String saClientId;
    private static String saClientSecret;
    private static int seq;

    @BeforeAll
    static void start() throws Exception {
        DataSource ds = TestPg.newDatabase("session_csrf_test");
        Migrator.migrate(ds);
        new Seeder(ds, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_EMAIL, ADMIN_EMAIL,
                Seeder.ENV_BOOTSTRAP_PASSWORD, PASSWORD))).run();
        roleLessUser(ds, "csrf-roleless@example.com");
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true",
                "FC_JWT_ISSUER", PUBLIC_BASE, "FLOWCATALYST_APP_KEY", Encryption.generateKey()));
        running = new Server(env, new Server.Mode.Platform(Pools.ofSingle(ds)), Frontend.embeddedOrNone(),
                new PrometheusRegistry()).start();
        port = running.apiPort();
        cookie = login(ADMIN_EMAIL);
        assertThat(cookie).startsWith("__Host-fc_session=");
        roleLessCookie = login("csrf-roleless@example.com");

        // A bearer for the same authority: a provisioned service account given super-admin
        // by the signed-in admin (same-origin cookie requests, which the guard lets through).
        var app = json(send("POST", "/api/applications", "{\"code\":\"csrfapp\",\"name\":\"csrfapp\"}", sameOrigin(JSON)), 201);
        var provisioned = json(send("POST", "/api/applications/" + app.get("id").asString() + "/provision-service-account", null,
                sameOrigin(null)), 201);
        var oauth = provisioned.get("serviceAccount").get("oauthClient");
        saClientId = oauth.get("clientId").asString();
        saClientSecret = oauth.get("clientSecret").asString();
        var sa = json(send("GET", "/api/service-accounts/code/app:csrfapp", null, "Cookie", cookie), 200);
        assertThat(send("PUT", "/api/service-accounts/" + sa.get("id").asString() + "/roles",
                "{\"roles\":[\"platform:super-admin\"]}", sameOrigin(JSON)).statusCode()).isEqualTo(200);
        var token = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/oauth/token"))
                .POST(HttpRequest.BodyPublishers.ofString(clientCredentials()))
                .header("Content-Type", "application/x-www-form-urlencoded").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        bearer = Json.MAPPER.readTree(token.body()).get("access_token").asString();
    }

    @AfterAll
    static void stop() {
        if (running != null) running.stop();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static final String JSON = "application/json";

    private static String base() {
        return "http://localhost:" + port;
    }

    private static String clientCredentials() {
        return "grant_type=client_credentials&client_id=" + URLEncoder.encode(saClientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(saClientSecret, StandardCharsets.UTF_8);
    }

    private static void roleLessUser(DataSource ds, String mail) {
        var now = Instant.now().atOffset(ZoneOffset.UTC);
        DSL.using(ds, SQLDialect.POSTGRES).insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, EntityType.PRINCIPAL.generate()).set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.SCOPE, "ANCHOR").set(IAM_PRINCIPALS.NAME, "Role-less").set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, mail).set(IAM_PRINCIPALS.EMAIL_DOMAIN, "example.com")
                .set(IAM_PRINCIPALS.PASSWORD_HASH, PasswordHash.hash(PASSWORD)).set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();
    }

    private static String login(String email) throws Exception {
        var r = send("POST", "/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", "Content-Type", JSON);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String sc = r.headers().allValues("set-cookie").stream().filter(c -> c.contains("fc_session=")).findFirst().orElseThrow();
        return sc.substring(0, sc.indexOf(';'));
    }

    private static HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            if (headers[i + 1] != null) b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String[] sameOrigin(String contentType) {
        return new String[] {"Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Content-Type", contentType};
    }

    /// The cookie from a sibling subdomain (same-site, so Lax sends it).
    private static String[] sibling(String contentType) {
        return new String[] {"Cookie", cookie, "Sec-Fetch-Site", "same-site", "Origin", SIBLING, "Content-Type", contentType};
    }

    private static JsonNode json(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return Json.MAPPER.readTree(r.body());
    }

    private static void assertRefused(HttpResponse<String> r, int status, String code) throws Exception {
        assertThat(json(r, status).get("error").asString()).as(r.body()).isEqualTo(code);
    }

    private static String newClient() throws Exception {
        seq++;
        return json(send("POST", "/api/clients", clientBody("seed" + seq), sameOrigin(JSON)), 201).get("id").asString();
    }

    private static String clientBody(String identifier) {
        return "{\"name\":\"CSRF " + identifier + "\",\"identifier\":\"csrf-" + identifier + "\"}";
    }

    private static String permissionBody(String action) {
        return "{\"application\":\"platform\",\"context\":\"csrf\",\"aggregate\":\"thing\",\"action\":\"" + action + "\",\"description\":\"x\"}";
    }

    private static boolean clientExists(String identifier) throws Exception {
        var r = send("GET", "/api/clients", null, "Cookie", cookie);
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body().contains("\"csrf-" + identifier + "\"");
    }

    private static boolean permissionExists(String action) throws Exception {
        var r = send("GET", "/bff/roles/permissions", null, "Cookie", cookie);
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body().contains("platform:csrf:thing:" + action);
    }

    private static String clientStatus(String id) throws Exception {
        var r = send("GET", "/api/clients/" + id, null, "Cookie", cookie);
        if (r.statusCode() == 404) return "GONE";
        return json(r, 200).get("status").asString();
    }

    /// HTTP/1.1 by hand, for the `Host` header the JDK client will not let a caller set.
    private static int rawStatus(String method, String path, String host, String body, String... headers) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            var req = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n");
            for (int i = 0; i < headers.length; i += 2) req.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            req.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            InputStream in = s.getInputStream();
            var buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            String response = buf.toString(StandardCharsets.UTF_8);
            return Integer.parseInt(response.substring(9, 12));
        }
    }

    // ── cross-origin cookie writes refused ───────────────────────────────

    @Test
    void crossOriginCookieWritesAreRefusedAndWriteNothing() throws Exception {
        // A text/plain JSON body (a no-preflight simple request) to a BFF route.
        assertRefused(send("POST", "/bff/roles/permissions", permissionBody("plain"), sibling("text/plain")), 403, "CROSS_ORIGIN_REQUEST");
        assertThat(permissionExists("plain")).isFalse();
        // Form and multipart bodies.
        assertRefused(send("POST", "/bff/roles/permissions", permissionBody("form"), sibling("application/x-www-form-urlencoded")),
                403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("POST", "/api/clients", clientBody("multipart"), sibling("multipart/form-data; boundary=x")),
                403, "CROSS_ORIGIN_REQUEST");
        assertThat(permissionExists("form")).isFalse();
        assertThat(clientExists("multipart")).isFalse();
        // A JSON body with no Content-Type (fetch with a Blob body: no preflight).
        assertRefused(send("POST", "/api/clients", clientBody("noct"), sibling(null)), 403, "CROSS_ORIGIN_REQUEST");
        assertThat(clientExists("noct")).isFalse();
        // Even a JSON body (only reachable cross-origin through a preflight, but refused all the same).
        assertRefused(send("POST", "/api/clients", clientBody("json"), sibling(JSON)), 403, "CROSS_ORIGIN_REQUEST");
        assertThat(clientExists("json")).isFalse();

        // Body-less actions: activate, and DELETE / PUT.
        String id = newClient();
        assertThat(send("POST", "/api/clients/" + id + "/deactivate", "{\"reason\":\"test\"}", sameOrigin(JSON)).statusCode()).isEqualTo(200);
        assertThat(clientStatus(id)).isEqualTo("INACTIVE");
        assertRefused(send("POST", "/api/clients/" + id + "/activate", null, sibling(null)), 403, "CROSS_ORIGIN_REQUEST");
        assertThat(clientStatus(id)).isEqualTo("INACTIVE");
        assertRefused(send("DELETE", "/api/clients/" + id, null, sibling(null)), 403, "CROSS_ORIGIN_REQUEST");
        assertThat(clientStatus(id)).isNotEqualTo("GONE");
        assertRefused(send("PUT", "/api/clients/" + id, "{\"name\":\"Renamed\"}", sibling("text/plain")), 403, "CROSS_ORIGIN_REQUEST");

        // Cross-site, a foreign Origin without Sec-Fetch-Site, and neither header.
        assertRefused(send("POST", "/api/clients", clientBody("xsite"), "Cookie", cookie, "Content-Type", JSON,
                "Sec-Fetch-Site", "cross-site", "Origin", "https://attacker.test"), 403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("POST", "/api/clients", clientBody("originonly"), "Cookie", cookie, "Content-Type", JSON,
                "Origin", SIBLING), 403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("POST", "/api/clients", clientBody("noheaders"), "Cookie", cookie, "Content-Type", JSON),
                403, "CROSS_ORIGIN_REQUEST");
        assertThat(clientExists("xsite")).isFalse();
        assertThat(clientExists("originonly")).isFalse();
        assertThat(clientExists("noheaders")).isFalse();

        // The guard runs before the profile gate: a role-less user gets the same answer.
        assertRefused(send("POST", "/api/clients", clientBody("roleless"), "Cookie", roleLessCookie, "Content-Type", JSON,
                "Sec-Fetch-Site", "same-site", "Origin", SIBLING), 403, "CROSS_ORIGIN_REQUEST");
        assertRefused(send("POST", "/api/clients", clientBody("roleless"), "Cookie", roleLessCookie, "Content-Type", JSON,
                "Sec-Fetch-Site", "same-origin"), 403, "NO_PLATFORM_ROLE");
    }

    // ── same-origin cookie writes allowed ────────────────────────────────

    @Test
    void sameOriginCookieWritesAndReadsAreAllowed() throws Exception {
        json(send("POST", "/api/clients", clientBody("same"), sameOrigin(JSON)), 201);
        json(send("POST", "/api/clients", clientBody("none"), "Cookie", cookie, "Content-Type", JSON, "Sec-Fetch-Site", "none"), 201);
        // Without Sec-Fetch-Site (a browser over plain HTTP), an Origin naming the Host.
        json(send("POST", "/api/clients", clientBody("originhost"), "Cookie", cookie, "Content-Type", JSON, "Origin", base()), 201);
        json(send("POST", "/bff/roles/permissions", permissionBody("sameorigin"), sameOrigin(JSON)), 201);
        assertThat(clientExists("same")).isTrue();
        assertThat(clientExists("none")).isTrue();
        assertThat(clientExists("originhost")).isTrue();
        assertThat(permissionExists("sameorigin")).isTrue();
        // A proxy that rewrites Host: the public base URL is trusted.
        assertThat(rawStatus("POST", "/api/clients", "internal:9000", clientBody("proxied"),
                "Cookie", cookie, "Content-Type", JSON, "Origin", PUBLIC_BASE)).isEqualTo(201);
        assertThat(clientExists("proxied")).isTrue();
        assertThat(rawStatus("POST", "/api/clients", "internal:9000", clientBody("proxied2"),
                "Cookie", cookie, "Content-Type", JSON, "Origin", "https://internal:9000.evil.example.com")).isEqualTo(403);
        // A body-less same-origin action.
        String id = newClient();
        assertThat(send("POST", "/api/clients/" + id + "/deactivate", "{\"reason\":\"test\"}", sameOrigin(JSON)).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/clients/" + id + "/activate", null, sameOrigin(null)).statusCode()).isEqualTo(200);
        assertThat(clientStatus(id)).isEqualTo("ACTIVE");
        // The SPA's artifact upload (raw bytes, the Publish drawer) passes the guard; the
        // handler then answers for itself (no such function here).
        var upload = send("PUT", "/api/functions/csrf:none/artifacts/sha256:" + "0".repeat(64), "\0asm",
                sameOrigin("application/octet-stream"));
        assertThat(upload.body()).doesNotContain("UNSUPPORTED_MEDIA_TYPE").doesNotContain("CROSS_ORIGIN_REQUEST");
        assertThat(upload.statusCode()).as(upload.body()).isNotIn(403, 415);
        // GET with foreign headers: safe methods are not checked.
        assertThat(send("GET", "/api/clients/" + id, null, sibling(null)).statusCode()).isEqualTo(200);
    }

    @Test
    void nonJsonCookieBodiesAre415OnEveryKindOfRoute() throws Exception {
        for (String ct : new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x",
                "application/json; charset"}) {
            assertRefused(send("POST", "/bff/roles/permissions", permissionBody("ct415"), sameOrigin(ct)), 415, "UNSUPPORTED_MEDIA_TYPE");
            assertRefused(send("POST", "/api/clients", clientBody("ct415"), sameOrigin(ct)), 415, "UNSUPPORTED_MEDIA_TYPE");
        }
        assertRefused(send("POST", "/api/clients", clientBody("ct415"), sameOrigin(null)), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(permissionExists("ct415")).isFalse();
        assertThat(clientExists("ct415")).isFalse();
        // A +json type is JSON.
        json(send("POST", "/api/clients", clientBody("plusjson"), sameOrigin("application/vnd.flowcatalyst+json; charset=utf-8")), 201);
    }

    // ── not affected ─────────────────────────────────────────────────────

    @Test
    void bearerRequestsAreNotAffected() throws Exception {
        String auth = "Bearer " + bearer;
        // A foreign Origin, cross-site.
        json(send("POST", "/api/clients", clientBody("bearer1"), "Authorization", auth, "Content-Type", JSON,
                "Sec-Fetch-Site", "cross-site", "Origin", "https://attacker.test"), 201);
        // No browser headers at all.
        json(send("POST", "/api/clients", clientBody("bearer2"), "Authorization", auth, "Content-Type", JSON), 201);
        // Beside the session cookie: the bearer wins, so the cookie rules do not apply.
        json(send("POST", "/api/clients", clientBody("bearer3"), "Authorization", auth, "Cookie", cookie,
                "Content-Type", JSON, "Sec-Fetch-Site", "same-site", "Origin", SIBLING), 201);
        // Body-less, and a non-JSON body (the handler decides, not the guard).
        String id = newClient();
        assertThat(send("POST", "/api/clients/" + id + "/deactivate", "{\"reason\":\"test\"}", "Authorization", auth,
                "Content-Type", "text/plain", "Origin", SIBLING).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/clients/" + id + "/activate", null, "Authorization", auth, "Origin", SIBLING).statusCode())
                .isEqualTo(200);
        assertThat(clientExists("bearer1")).isTrue();
        assertThat(clientExists("bearer2")).isTrue();
        assertThat(clientExists("bearer3")).isTrue();
    }

    @Test
    void oauthTokenWithTheCookieCrossSiteIsNotRefusedByTheRule() throws Exception {
        var r = send("POST", "/oauth/token", clientCredentials(), "Cookie", cookie, "Content-Type", "application/x-www-form-urlencoded",
                "Sec-Fetch-Site", "cross-site", "Origin", "https://attacker.test");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(r.body()).has("access_token")).isTrue();
    }

    @Test
    void anInvalidCookieIsNotASession() throws Exception {
        // Unauthenticated, so the handler's own check answers, not the guard.
        assertRefused(send("POST", "/api/clients", clientBody("badcookie"), "Cookie", "__Host-fc_session=not-a-token",
                "Content-Type", "text/plain", "Sec-Fetch-Site", "cross-site", "Origin", "https://attacker.test"), 403, "UNAUTHENTICATED");
        assertThat(clientExists("badcookie")).isFalse();
    }

    @Test
    void publicRoutesAreOutsideTheGuard() throws Exception {
        // The dispatch callback (HMAC) is public: refused by its own signature check, never by the guard.
        var r = send("POST", "/api/dispatch/process", "{}", "Cookie", cookie, "Content-Type", "text/plain",
                "Sec-Fetch-Site", "cross-site", "Origin", "https://attacker.test");
        assertThat(r.statusCode()).isNotIn(403, 415);
        assertThat(r.body()).doesNotContain("CROSS_ORIGIN_REQUEST").doesNotContain("UNSUPPORTED_MEDIA_TYPE");
    }
}

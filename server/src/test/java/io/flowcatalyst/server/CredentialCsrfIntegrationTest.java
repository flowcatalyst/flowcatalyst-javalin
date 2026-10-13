package io.flowcatalyst.server;

import io.flowcatalyst.platform.seed.Seeder;
import io.flowcatalyst.platform.shared.auth.CredentialCsrf;
import io.flowcatalyst.platform.shared.database.Migrator;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/// Login CSRF (`CredentialCsrf`, Go F16 follow-up `internal/server/logincsrf_pg_test.go`)
/// through the REAL composed platform: the public routes that sign a browser in, or
/// set its credentials, take only a JSON body and refuse a request a browser marks as
/// from another origin; a request with neither header passes (a non-browser caller).
class CredentialCsrfIntegrationTest {

    private static final String PUBLIC_BASE = "https://platform.example.com";
    private static final String EMAIL = "login-csrf-admin@example.com";
    private static final String PASSWORD = "Login-Csrf-Passw0rd-long!";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static Server.Running running;
    private static Server server;
    private static int port;

    /// Every unsafe route outside `/api/` and `/bff/` that [CredentialCsrf#ROUTES] does not
    /// guard, with the reason it is left to other defences (Go `notCredentialGuarded`). A new
    /// public unsafe route fails [#everyPublicUnsafeRouteIsDecided] until it is placed in one
    /// or the other.
    static final Map<String, String> NOT_CREDENTIAL_GUARDED = new TreeMap<>(Map.ofEntries(
            Map.entry("POST /auth/check-domain", "read-only lookup"),
            Map.entry("POST /auth/logout", "issues nothing; GET /auth/oidc/session/end (RP-initiated logout) clears the cookie by design"),
            Map.entry("POST /auth/refresh", "refresh token in the body, tokens in the JSON body, no cookie"),
            Map.entry("POST /auth/change-password", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/change-password/send-email-code", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/client/switch", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/2fa/methods/totp/begin", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/2fa/methods/totp/confirm", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/2fa/methods/email/begin", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/2fa/methods/email/confirm", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/2fa/recovery-codes/regenerate", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("DELETE /auth/2fa/methods/{method}", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("DELETE /auth/2fa/trusted-devices/{id}", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/webauthn/register/begin", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /auth/webauthn/register/complete", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("DELETE /auth/webauthn/credentials/{id}", "authenticated (SessionCsrf for the cookie)"),
            Map.entry("POST /oauth/token", "OAuth: form-encoded by spec, client-authenticated, PKCE; no cookie"),
            Map.entry("POST /oauth/introspect", "OAuth: form-encoded by spec, client-authenticated"),
            Map.entry("POST /oauth/revoke", "OAuth: form-encoded by spec, client-authenticated"),
            Map.entry("POST /oauth/userinfo", "OIDC: bearer access token"),
            Map.entry("POST /control/functions/events", "function runner bearer token"),
            Map.entry("POST /control/functions/heartbeat", "function runner bearer token")));

    @BeforeAll
    static void start() {
        DataSource ds = TestPg.newDatabase("credential_csrf_test");
        Migrator.migrate(ds);
        new Seeder(ds, new EnvReader(Map.of(Seeder.ENV_BOOTSTRAP_EMAIL, EMAIL, Seeder.ENV_BOOTSTRAP_PASSWORD, PASSWORD))).run();
        Env env = Env.load(Map.of("FC_API_PORT", "0", "FC_METRICS_PORT", "0", "FC_PLATFORM_ENABLED", "true",
                "FC_JWT_ISSUER", PUBLIC_BASE, "FLOWCATALYST_APP_KEY", Encryption.generateKey()));
        server = new Server(env, new Server.Mode.Platform(Pools.ofSingle(ds)), Server.Spa.none(), new PrometheusRegistry());
        running = server.start();
        port = running.apiPort();
    }

    @AfterAll
    static void stop() {
        if (running != null) running.stop();
    }

    private static HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            if (headers[i + 1] != null) b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String code(HttpResponse<String> r) throws Exception {
        try {
            return Json.MAPPER.readTree(r.body()).path("error").asString("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean setsSession(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream().anyMatch(c -> c.contains("fc_session=") && !c.contains("Max-Age=0"));
    }

    private static String credentials() {
        return "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    // ── the route pin ────────────────────────────────────────────────────

    @Test
    void everyPublicUnsafeRouteIsDecided() {
        var undecided = new TreeSet<String>();
        var seen = new TreeSet<String>();
        for (var reg : server.buildApi().registry().registrations()) {
            String m = reg.method();
            if (m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS")) continue;
            if (reg.path().startsWith("/api/") || reg.path().startsWith("/bff/")) continue;
            String k = m + " " + reg.path();
            seen.add(k);
            boolean guarded = m.equals("POST") && CredentialCsrf.ROUTES.contains(reg.path());
            if (!guarded && !NOT_CREDENTIAL_GUARDED.containsKey(k)) undecided.add(k);
        }
        assertThat(undecided).as("public unsafe routes with no decision: add each to CredentialCsrf.ROUTES or NOT_CREDENTIAL_GUARDED")
                .isEmpty();
        for (String p : CredentialCsrf.ROUTES) {
            assertThat(seen).as("CredentialCsrf.ROUTES names a route that is not mounted").contains("POST " + p);
        }
        for (String k : NOT_CREDENTIAL_GUARDED.keySet()) {
            assertThat(seen).as("NOT_CREDENTIAL_GUARDED names a route that is not mounted").contains(k);
        }
    }

    // ── refusals on every guarded route ──────────────────────────────────

    @Test
    void guardedRoutesRefuseWhatAnotherOriginCanSend() throws Exception {
        var failures = new ArrayList<String>();
        for (String path : CredentialCsrf.ROUTES) {
            String[][] crossOrigin = {
                    {"text/plain", "cross-site", "https://attacker.test"},
                    {"application/json", "cross-site", "https://attacker.test"},
                    {"text/plain", "same-site", "https://evil.example.com"},
                    {"application/json", null, "https://evil.example.com"},
                    {"application/json", null, "null"},
            };
            for (String[] c : crossOrigin) {
                var r = post(path, "{\"email\":\"a@b.c\",\"password\":\"x\"}", "Content-Type", c[0], "Sec-Fetch-Site", c[1], "Origin", c[2]);
                if (r.statusCode() != 403 || !code(r).equals("CROSS_ORIGIN_REQUEST") || setsSession(r)) {
                    failures.add(path + " " + String.join("/", java.util.Arrays.asList(c)) + " -> " + r.statusCode() + " " + r.body());
                }
            }
            for (String ct : new String[] {"text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", null}) {
                var r = post(path, "{}", "Content-Type", ct, "Sec-Fetch-Site", "same-origin");
                if (r.statusCode() != 415 || !code(r).equals("UNSUPPORTED_MEDIA_TYPE")) {
                    failures.add(path + " same-origin " + ct + " -> " + r.statusCode() + " " + r.body());
                }
                // JSON only for every caller: a non-browser too.
                var curl = post(path, "{}", "Content-Type", ct);
                if (curl.statusCode() != 415) failures.add(path + " no headers " + ct + " -> " + curl.statusCode());
            }
            // Allowed through the guard (the handler then answers for itself).
            for (String[] ok : new String[][] {
                    {"Sec-Fetch-Site", "same-origin"}, {"Sec-Fetch-Site", "none"},
                    {"Origin", "http://localhost:" + port}, {"Origin", PUBLIC_BASE}, {"X-None", "x"}}) {
                var r = post(path, "{}", "Content-Type", "application/json", ok[0], ok[1]);
                if (code(r).equals("CROSS_ORIGIN_REQUEST") || code(r).equals("UNSUPPORTED_MEDIA_TYPE")) {
                    failures.add(path + " allowed " + ok[0] + "=" + ok[1] + " -> " + r.statusCode() + " " + r.body());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    // ── a real login ─────────────────────────────────────────────────────

    @Test
    void aRealLoginIsRefusedFromElsewhereAndAllowedForTheSpaAndForCurl() throws Exception {
        // The attack: a cross-site <form enctype="text/plain"> with the attacker's credentials.
        var xsite = post("/auth/login", credentials(), "Content-Type", "text/plain", "Sec-Fetch-Site", "cross-site",
                "Origin", "https://attacker.test");
        assertThat(xsite.statusCode()).as(xsite.body()).isEqualTo(403);
        assertThat(setsSession(xsite)).isFalse();
        var sibling = post("/auth/login", credentials(), "Content-Type", "application/json", "Sec-Fetch-Site", "same-site",
                "Origin", "https://evil.example.com");
        assertThat(sibling.statusCode()).isEqualTo(403);
        assertThat(setsSession(sibling)).isFalse();
        var plain = post("/auth/login", credentials(), "Content-Type", "text/plain", "Sec-Fetch-Site", "same-origin");
        assertThat(plain.statusCode()).isEqualTo(415);
        assertThat(setsSession(plain)).isFalse();

        var spa = post("/auth/login", credentials(), "Content-Type", "application/json", "Sec-Fetch-Site", "same-origin",
                "Origin", "http://localhost:" + port);
        assertThat(spa.statusCode()).as(spa.body()).isEqualTo(200);
        assertThat(setsSession(spa)).isTrue();
        var curl = post("/auth/login", credentials(), "Content-Type", "application/json");
        assertThat(curl.statusCode()).isEqualTo(200);
        assertThat(setsSession(curl)).isTrue();
        var originOnly = post("/auth/login", credentials(), "Content-Type", "application/json", "Origin", "http://localhost:" + port);
        assertThat(originOnly.statusCode()).isEqualTo(200);
        // A proxy that rewrites Host: the public base URL is trusted.
        assertThat(rawStatus("/auth/login", "internal:9000", credentials(), "Content-Type", "application/json",
                "Origin", PUBLIC_BASE)).isEqualTo(200);
        assertThat(rawStatus("/auth/login", "internal:9000", credentials(), "Content-Type", "application/json",
                "Origin", "https://attacker.test")).isEqualTo(403);
    }

    @Test
    void checkDomainAndLogoutAreNotGuarded() throws Exception {
        var check = post("/auth/check-domain", "{\"email\":\"x@example.com\"}", "Content-Type", "application/json",
                "Sec-Fetch-Site", "cross-site", "Origin", "https://attacker.test");
        assertThat(code(check)).isNotEqualTo("CROSS_ORIGIN_REQUEST");
        assertThat(check.statusCode()).isEqualTo(200);
        var logout = post("/auth/logout", null, "Sec-Fetch-Site", "cross-site", "Origin", "https://attacker.test");
        assertThat(code(logout)).isNotEqualTo("CROSS_ORIGIN_REQUEST");
        assertThat(logout.statusCode()).isLessThan(400);
    }

    private static int rawStatus(String path, String host, String body, String... headers) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            var req = new StringBuilder("POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n");
            for (int i = 0; i < headers.length; i += 2) req.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            req.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            var buf = new ByteArrayOutputStream();
            s.getInputStream().transferTo(buf);
            return Integer.parseInt(buf.toString(StandardCharsets.UTF_8).substring(9, 12));
        }
    }
}

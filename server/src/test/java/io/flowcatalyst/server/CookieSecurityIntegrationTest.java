package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.database.Pools;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/cookie-hardening.md` §2: "the secure decision is pinned end to
/// end" through the REAL composed [Platform] (`Server.Mode.Platform`, exactly
/// what `Main` builds) — not a hand-wired `LoginApi.State` the way
/// `LoginApiTest` runs. The mutant this guards against (inverting
/// `cookiesSecure` in `Platform.register`/`buildAuthenticator`) lives in code
/// a hand-wired harness never touches, so only a real boot pins it.
class CookieSecurityIntegrationTest {

    private static final DSLContext DB = DSL.using(TestPg.dataSource(), SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PASSWORD = "correct horse battery staple";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Server.Running secure;    // default environment: test headers off (a real deployment)
    private static Server.Running insecure;  // test headers on + dev mode (fcdev's own environment)
    private static String email;
    private static String principalId;

    @BeforeAll
    static void start() {
        email = "cookie-hardening-" + RUN + "@example.com";
        principalId = principal(email, PasswordHash.hash(PASSWORD));

        secure = boot(Map.of());
        insecure = boot(Map.of("FC_AUTH_ALLOW_TEST_HEADERS", "true", "FLOWCATALYST_DEV_MODE", "true"));
    }

    @AfterAll
    static void stop() {
        secure.stop();
        insecure.stop();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(principalId)).execute();
    }

    private static Server.Running boot(Map<String, String> extra) {
        var kv = new HashMap<>(Map.of(
                "FC_API_PORT", "0",
                "FC_METRICS_PORT", "0",
                "FC_PLATFORM_ENABLED", "true"));
        kv.putAll(extra);
        Env env = Env.load(kv);
        return new Server(env, new Server.Mode.Platform(Pools.ofSingle(TestPg.dataSource())), Frontend.embeddedOrNone(),
                new PrometheusRegistry()).start();
    }

    private static String principal(String mail, String passwordHash) {
        String id = EntityType.PRINCIPAL.generate();
        var now = Instant.now().atOffset(ZoneOffset.UTC);
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "CLIENT")
                .set(IAM_PRINCIPALS.NAME, "Cookie Hardening " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, mail).set(IAM_PRINCIPALS.EMAIL_DOMAIN, mail.substring(mail.indexOf('@') + 1))
                .set(IAM_PRINCIPALS.PASSWORD_HASH, passwordHash).set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.CREATED_AT, now).set(IAM_PRINCIPALS.UPDATED_AT, now)
                .execute();
        return id;
    }

    private static HttpResponse<String> login(int port) {
        var body = Json.writeLine(Map.of("email", email, "password", PASSWORD));
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the default (deployed) environment mints __Host-fc_session, Secure, no Domain")
    void defaultEnvironmentMintsTheSecureCookie() {
        var r = login(secure.apiPort());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

        String cookie = setCookie(r);
        // Mutant: invert `cookiesSecure` in Platform — this flips to `fc_session`/no
        // `Secure`, and the assertion below fails.
        assertThat(cookie).startsWith("__Host-fc_session=")
                .contains("Secure").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/")
                .doesNotContain("Domain=");
    }

    @Test
    @DisplayName("test headers on (fcdev's own environment) mints the plain fc_session, no Secure")
    void testHeaderEnvironmentMintsThePlainCookie() {
        var r = login(insecure.apiPort());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

        String cookie = setCookie(r);
        assertThat(cookie).startsWith("fc_session=").doesNotContain("Secure").doesNotContain("__Host-");
    }

    private static String setCookie(HttpResponse<String> r) {
        return r.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("fc_session=") || c.startsWith("__Host-fc_session="))
                .findFirst().orElseThrow(() -> new AssertionError("no session cookie in " + r.headers().map()));
    }
}

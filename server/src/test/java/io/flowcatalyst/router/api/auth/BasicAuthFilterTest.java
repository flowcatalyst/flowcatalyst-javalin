package io.flowcatalyst.router.api.auth;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end HTTP coverage of [BasicAuthFilter] (`docs/spec/router.md`
/// §9.7) — every app below is mounted under a non-root prefix (`/router`),
/// which is the scenario Go's own tests miss (they mount at root) and where
/// the documented Go defect would otherwise demand credentials on every
/// probe.
class BasicAuthFilterTest {

    private static final String PREFIX = "/router";
    private static final String USER = "admin";
    private static final String PASS = "s3cret";
    private static final String VALID_AUTH = basic(USER, PASS);

    /// Every path spec §9.7 names as public, including the glob-shaped
    /// `/openapi*.{json,yaml}` and the `/docs` subtree.
    private static final List<String> PUBLIC_PATHS = List.of(
            "/health", "/q/health",
            "/health/live", "/health/ready", "/health/startup",
            "/q/health/live", "/q/health/ready",
            "/metrics", "/q/metrics",
            "/ready",
            "/openapi.json", "/openapi.yaml", "/openapi-3.0.json", "/openapi-3.1.yaml",
            "/docs", "/docs/index.html");

    private static TestHttp enabledApp;
    private static TestHttp noneModeApp;
    private static TestHttp emptyUserApp;

    @BeforeAll
    static void start() {
        enabledApp = buildApp(new BasicAuthFilter("", USER, PASS, PREFIX));
        noneModeApp = buildApp(new BasicAuthFilter("NoNe", USER, PASS, PREFIX));
        emptyUserApp = buildApp(new BasicAuthFilter("", "", "", PREFIX));
    }

    @AfterAll
    static void stop() {
        enabledApp.close();
        noneModeApp.close();
        emptyUserApp.close();
    }

    private static TestHttp buildApp(BasicAuthFilter filter) {
        return new TestHttp(cfg -> {
            BasicAuthFilter.register(cfg.routes, filter);
            cfg.routes.get(PREFIX + "/monitoring/health", ctx -> ctx.result("protected-ok"));
            for (String p : PUBLIC_PATHS) {
                cfg.routes.get(PREFIX + p, ctx -> ctx.result("public-ok"));
            }
        });
    }

    private static String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
    }

    @Test
    @DisplayName("a protected path 401s without credentials")
    void protectedPathRequiresAuth() {
        var r = enabledApp.get(PREFIX + "/monitoring/health");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).doesNotContain("protected-ok");
    }

    @Test
    @DisplayName("the 401 names the realm in WWW-Authenticate")
    void unauthorizedHeaderNamesRealm() {
        var r = enabledApp.get(PREFIX + "/monitoring/health");
        assertThat(r.headers().firstValue("WWW-Authenticate"))
                .contains("Basic realm=\"FlowCatalyst Router\", charset=\"UTF-8\"");
    }

    @Test
    @DisplayName("correct credentials reach the protected path")
    void correctCredentialsSucceed() {
        var r = enabledApp.get(PREFIX + "/monitoring/health", "Authorization", VALID_AUTH);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("protected-ok");
    }

    @Test
    @DisplayName("a wrong password 401s with the correct WWW-Authenticate header")
    void wrongPassword401s() {
        var r = enabledApp.get(PREFIX + "/monitoring/health", "Authorization", basic(USER, "not-the-password"));
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate"))
                .contains("Basic realm=\"FlowCatalyst Router\", charset=\"UTF-8\"");
    }

    @Test
    @DisplayName("a wrong username 401s even though the password is correct")
    void wrongUsername401s() {
        var r = enabledApp.get(PREFIX + "/monitoring/health", "Authorization", basic("not-" + USER, PASS));
        assertThat(r.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("every public path bypasses auth when the router is mounted under a prefix")
    void publicPathsBypassUnderPrefix() {
        for (String p : PUBLIC_PATHS) {
            var r = enabledApp.get(PREFIX + p);
            assertThat(r.statusCode())
                    .as("public path %s under prefix %s must not require credentials", p, PREFIX)
                    .isEqualTo(200);
            assertThat(r.body()).isEqualTo("public-ok");
        }
    }

    @Test
    @DisplayName("AUTH_MODE=NONE disables the filter even when a username/password are configured")
    void authModeNoneDisables() {
        var r = noneModeApp.get(PREFIX + "/monitoring/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("protected-ok");
    }

    @Test
    @DisplayName("an empty username disables the filter")
    void emptyUsernameDisables() {
        var r = emptyUserApp.get(PREFIX + "/monitoring/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("protected-ok");
    }
}

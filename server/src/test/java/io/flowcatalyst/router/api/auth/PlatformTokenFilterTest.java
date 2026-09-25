package io.flowcatalyst.router.api.auth;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.auth.jwks.TestJwks;
import io.flowcatalyst.router.api.RouterApi;
import io.flowcatalyst.router.api.dashboard.DashboardHandler;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.WarningStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router-api-auth.md` rules 1, 2, 4, 5 and 7, over HTTP against the
/// real router routes. The guard is chosen by [RouterAuth#install] the same way
/// `Server` chooses it, and the tokens are real RS256 JWTs verified against a
/// JWKS served by [TestJwks].
class PlatformTokenFilterTest {

    private static final String PREFIX = "/router";
    private static final Instant IN_AN_HOUR = Instant.now().plusSeconds(3600);

    private static TestJwks jwks;
    /// Production shape: platform tokens, no dev routes.
    private static TestHttp guarded;
    /// No platform URL: must fail closed.
    private static TestHttp unverifiable;
    /// Dev mode with `AUTH_MODE=NONE`: §9.7, open, dev routes mounted.
    private static TestHttp devOpen;
    private static RouterAuth.Installed guardedInstall;

    @BeforeAll
    static void start() throws Exception {
        jwks = new TestJwks();
        var state = new RouterApi.State(null, new InFlightTracker(Clock.systemUTC()), new WarningStore(Clock.systemUTC()),
                null, null, null, null, PREFIX, null, null, null, null);
        guarded = TestHttp.routes(routes -> {
            // AUTH_MODE=NONE and a Basic user are set, as the deployed router's IaC does:
            // both must be ignored outside dev mode.
            guardedInstall = RouterAuth.install(routes,
                    new RouterAuth.Settings(false, "NONE", "admin", "pw", PREFIX, jwks.issuer));
            RouterApi.register(routes, state);
            DashboardHandler.register(routes, PREFIX);
        });
        unverifiable = TestHttp.routes(routes -> {
            RouterAuth.install(routes, new RouterAuth.Settings(false, "", "", "", PREFIX, ""));
            RouterApi.register(routes, state);
        });
        devOpen = TestHttp.routes(routes -> {
            RouterAuth.install(routes, new RouterAuth.Settings(true, "NONE", "", "", PREFIX, ""));
            RouterApi.register(routes, state);
            RouterApi.registerDevRoutes(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        guarded.close();
        unverifiable.close();
        devOpen.close();
        jwks.close();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String api(String scope) {
        return bearer(jwks.mintApi("prn_caller", scope, IN_AN_HOUR));
    }

    private static final String VIEW = Permission.ROUTER_VIEW.code();
    private static final String OPERATE = Permission.ROUTER_OPERATE.code();

    @Test
    @DisplayName("health, metrics and the dashboard page answer without a token")
    void publicRoutesNeedNoToken() {
        assertThat(guarded.get(PREFIX + "/health/live").statusCode()).isEqualTo(200);
        assertThat(guarded.get(PREFIX + "/dashboard.html").statusCode()).isEqualTo(200);
        assertThat(guarded.get(PREFIX + "/monitoring/dashboard").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("no token is 401 with a Bearer challenge the dashboard can read")
    void noTokenIsABearerChallenge() {
        var r = guarded.get(PREFIX + "/monitoring/pools");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"FlowCatalyst Router\"");
        assertThat(r.headers().firstValue("X-Auth-Mode")).hasValue("BEARER");
    }

    @Test
    @DisplayName("AUTH_MODE=NONE and Basic credentials are ignored outside dev mode, and reported as ignored")
    void devSettingsAreIgnoredOutsideDevMode() {
        assertThat(guardedInstall).isEqualTo(new RouterAuth.Installed.PlatformTokens(true,
                List.of("AUTH_MODE", "FC_ROUTER_AUTH_USER", "FC_ROUTER_AUTH_PASS")));
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:pw".getBytes());
        assertThat(guarded.get(PREFIX + "/monitoring/pools", "Authorization", basic).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("view reads the router; it cannot operate it")
    void viewReadsButDoesNotOperate() {
        assertThat(guarded.get(PREFIX + "/monitoring/pools", "Authorization", api(VIEW)).statusCode()).isEqualTo(200);
        var reset = guarded.post(PREFIX + "/monitoring/circuit-breakers/reset-all", null, "Authorization", api(VIEW));
        assertThat(reset.statusCode()).isEqualTo(403);
        assertThat(reset.body()).contains("PERMISSION_REQUIRED").contains(OPERATE);
        assertThat(guarded.post(PREFIX + "/messages", "{}", "Authorization", api(VIEW)).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the in-flight batch check is a read even though it is a POST")
    void checkBatchIsARead() {
        var r = guarded.post(PREFIX + "/monitoring/in-flight-messages/check-batch", "{\"messageIds\":[\"m1\"]}",
                "Authorization", api(VIEW));
        assertThat(r.statusCode()).as(r.body()).isNotIn(401, 403);
    }

    @Test
    @DisplayName("operate passes the guard for writes (the bare router then answers 503: no breakers wired)")
    void operatePassesForWrites() {
        var reset = guarded.post(PREFIX + "/monitoring/circuit-breakers/reset-all", null, "Authorization", api(OPERATE));
        assertThat(reset.statusCode()).isEqualTo(503);
        assertThat(guarded.get(PREFIX + "/monitoring/pools", "Authorization", api(OPERATE)).statusCode())
                .as("operate alone does not include view").isEqualTo(403);
    }

    @Test
    @DisplayName("the super-admin wildcard holds both")
    void wildcardHoldsBoth() {
        String superAdmin = api("platform:*:*:*");
        assertThat(guarded.get(PREFIX + "/monitoring/pools", "Authorization", superAdmin).statusCode()).isEqualTo(200);
        assertThat(guarded.post(PREFIX + "/monitoring/circuit-breakers/reset-all", null, "Authorization", superAdmin)
                .statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("identity tokens, tokens with no token_use, expired tokens and foreign signatures are all 401")
    void onlyAValidApiTokenAuthenticates() {
        String identity = bearer(jwks.mintWithTokenUse("identity", "prn_caller", "ANCHOR", List.of(), IN_AN_HOUR));
        String noTokenUse = bearer(jwks.mint("prn_caller", "USER", "ANCHOR", VIEW, List.of(), List.of(), true, IN_AN_HOUR));
        String expired = bearer(jwks.mintApi("prn_caller", VIEW, Instant.now().minusSeconds(60)));
        String foreign = bearer(TestJwks.mint((RSAPrivateKey) TestJwks.foreignKeyPair().getPrivate(), "kid-1",
                jwks.discoveryIssuer, "prn_caller", "SERVICE", "ANCHOR", VIEW, List.of(), List.of(), List.of(), true,
                IN_AN_HOUR, "api"));
        for (String token : List.of(identity, noTokenUse, expired, foreign)) {
            assertThat(guarded.get(PREFIX + "/monitoring/pools", "Authorization", token).statusCode())
                    .as(token.substring(0, 20)).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("with no platform to verify against, even a valid token is refused")
    void noPlatformFailsClosed() {
        assertThat(unverifiable.get(PREFIX + "/monitoring/pools", "Authorization", api(VIEW)).statusCode()).isEqualTo(401);
        assertThat(unverifiable.get(PREFIX + "/health/live").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the mock, benchmark and seed routes are absent outside dev mode, present in it")
    void devRoutesExistOnlyInDevMode() {
        // Read off the registrations, not a status: this harness has no 404 mapper, so an
        // unmatched path answers 500 here (the real server answers 404).
        java.util.function.Predicate<io.flowcatalyst.http.RouteRegistry.Registration> dev = r ->
                r.path().startsWith(PREFIX + "/api/test/") || r.path().startsWith(PREFIX + "/api/benchmark/")
                        || r.path().equals(PREFIX + "/api/seed/messages");
        assertThat(guarded.registry().registrations()).noneMatch(dev);
        assertThat(guarded.registry().registrations()).anyMatch(r -> r.path().equals(PREFIX + "/messages"));
        assertThat(devOpen.registry().registrations().stream().filter(dev).count()).isEqualTo(15);
        assertThat(devOpen.post(PREFIX + "/api/test/fast", null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("dev mode keeps §9.7: AUTH_MODE=NONE is open")
    void devModeKeepsBasicAuthSemantics() {
        assertThat(devOpen.get(PREFIX + "/monitoring/pools").statusCode()).isEqualTo(200);
    }
}

package io.flowcatalyst.platform.shared.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// The Go `platformmw.Authenticator` decision tree, end to end through Javalin.
class AuthenticatorTest {

    private static final String ISSUER = "http://localhost:8080";
    private static SigningKeys keys;
    private static SigningKeys otherKeys;
    private static TestHttp permissive;   // test headers allowed
    private static TestHttp strict;       // production config

    /// Cookie sessions resolve only for `prn_cookie`.
    private static final ClaimsResolver RESOLVER = principalId ->
            "prn_cookie".equals(principalId)
                    ? Optional.of(new AuthContext(principalId, Scope.ANCHOR, "c@x.io", List.of(), List.of("platform:super-admin"), List.of(), true, List.of("*")))
                    : Optional.empty();

    @BeforeAll
    static void start() {
        keys = SigningKeys.generateEphemeral();
        otherKeys = SigningKeys.generateEphemeral();
        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(keys.publicKey())));
        permissive = server(new Authenticator(verifier, RESOLVER, Authenticator.Config.of(true)));
        strict = server(new Authenticator(verifier, RESOLVER, Authenticator.Config.PRODUCTION));
    }

    private static TestHttp server(Authenticator auth) {
        return TestHttp.routes(routes -> {
            routes.before("/api/*", auth);
            routes.get("/api/whoami", ctx -> {
                var ac = Auth.from(ctx);
                ctx.result(ac == null ? "anon" : ac.principalId() + (ac.isAnchor() ? ":anchor" : ""));
            });
            // Reports the SCOPE DECISION, not the raw claim, so the assertion
            // is about what the platform will actually allow.
            routes.get("/api/scope-check", ctx -> {
                var ac = Auth.from(ctx);
                ctx.result(ac == null ? "anon"
                        : "client=" + ac.canAccessClient(ctx.queryParam("clientId"))
                                + ",app=" + ac.canAccessApplication(ctx.queryParam("applicationId")));
            });
            // `docs/spec/portal-apps.md` §6, Part A J6: what principal type the
            // context actually carries, not just whether one is bound.
            routes.get("/api/principal-type", ctx -> {
                var ac = Auth.from(ctx);
                ctx.result(ac == null ? "anon" : String.valueOf(ac.principalType()));
            });
        });
    }

    @AfterAll
    static void stop() {
        permissive.close();
        strict.close();
    }

    private static String mint(SigningKeys with, Map<String, Object> extra) throws Exception {
        var b = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(ISSUER).subject("prn_bearer")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .issueTime(new Date())
                .jwtID("jti-1")
                .claim("type", "USER").claim("tier", "ANCHOR").claim("scope", "platform:admin")
                .claim("clients", List.of()).claim("roles", List.of("platform:super-admin"))
                .claim("applications", List.of()).claim("all_applications", true)
                .claim("token_use", "api");
        extra.forEach(b::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(with.kid()).build(), b.build());
        jwt.sign(new RSASSASigner(with.privateKey()));
        return jwt.serialize();
    }

    @Test
    @DisplayName("a token carrying \"{id}:{label}\" pairs still grants access to the bare id")
    void pairFormClaimsGrantAccess() throws Exception {
        // The clients claim has always carried "{clientId}:{clientIdentifier}"
        // pairs, and applications joined it as "{applicationId}:{code}". The
        // platform compares against BARE ids, so without a parser every scope
        // check failed closed — canAccessClient("clt_x") asking whether the
        // list contained "clt_x" when it contained "clt_x:acme".
        //
        // Self-minted tokens hid it: the same code wrote and read bare ids, so
        // it was consistent with itself. It only shows against a token minted
        // by the other implementation, which is the whole point of a drop-in
        // replacement — and it fails CLOSED, so it reads as a permissions
        // problem rather than a parsing one.
        var token = mint(keys, Map.of(
                "tier", "CLIENT",
                "clients", List.of("clt_x:acme"),
                "applications", List.of("app_1:orders"),
                "all_applications", false));

        var r = strict.get("/api/scope-check?clientId=clt_x&applicationId=app_1",
                "Authorization", "Bearer " + token);
        assertThat(r.body()).isEqualTo("client=true,app=true");
    }

    @Test
    @DisplayName("the bare-id form still works, so tokens minted before the pair form stay valid")
    void bareFormClaimsStillGrantAccess() throws Exception {
        var token = mint(keys, Map.of(
                "tier", "CLIENT",
                "clients", List.of("clt_y"),
                "applications", List.of("app_2"),
                "all_applications", false));

        assertThat(strict.get("/api/scope-check?clientId=clt_y&applicationId=app_2",
                "Authorization", "Bearer " + token).body()).isEqualTo("client=true,app=true");
    }

    @Test
    @DisplayName("the \"*\" sentinel on applications grants every application without all_applications")
    void wildcardApplicationsSentinel() throws Exception {
        // "*" is the claim's own way of saying "every one"; all_applications is
        // the older boolean. Either alone must grant it, or a token carrying
        // only the newer form silently reaches nothing.
        var token = mint(keys, Map.of(
                "tier", "CLIENT",
                "clients", List.of("clt_z:zeta"),
                "applications", List.of("*"),
                "all_applications", false));

        assertThat(strict.get("/api/scope-check?clientId=clt_z&applicationId=anything-at-all",
                "Authorization", "Bearer " + token).body()).isEqualTo("client=true,app=true");
    }

    @Test
    @DisplayName("a pair for one id does not grant a different id")
    void pairFormDoesNotOverGrant() throws Exception {
        var token = mint(keys, Map.of(
                "tier", "CLIENT",
                "clients", List.of("clt_x:acme"),
                "applications", List.of("app_1:orders"),
                "all_applications", false));

        assertThat(strict.get("/api/scope-check?clientId=acme&applicationId=orders",
                "Authorization", "Bearer " + token).body())
                .as("the LABEL half must not be usable as an id").isEqualTo("client=false,app=false");
    }

    @Test
    void noCredentialsProceedsUnauthenticated() {
        assertThat(strict.get("/api/whoami").body()).isEqualTo("anon");
    }

    @Test
    void validBearerBindsTheAuthContext() throws Exception {
        var r = strict.get("/api/whoami", "Authorization", "Bearer " + mint(keys, Map.of()));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("prn_bearer:anchor");
        // scheme is case-insensitive
        assertThat(strict.get("/api/whoami", "Authorization", "bearer " + mint(keys, Map.of())).body()).isEqualTo("prn_bearer:anchor");
    }

    @Test
    void identityTokensAreRejectedAsApiCredentials() throws Exception {
        var r = strict.get("/api/whoami", "Authorization", "Bearer " + mint(keys, Map.of("token_use", "identity")));
        assertInvalidToken(r);
        assertThat(r.body()).contains(Authenticator.IDENTITY_TOKEN_REJECTED);
    }

    @Test
    void badSignatureAndWrongIssuerHardFail401() throws Exception {
        assertInvalidToken(strict.get("/api/whoami", "Authorization", "Bearer " + mint(otherKeys, Map.of())));
        assertInvalidToken(strict.get("/api/whoami", "Authorization", "Bearer " + mint(keys, Map.of("iss", "http://evil"))));
        assertInvalidToken(strict.get("/api/whoami", "Authorization", "Bearer not.a.jwt"));
    }

    private static void assertInvalidToken(HttpResponse<String> r) {
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Bearer error=\"invalid_token\"");
        assertThat(r.body()).startsWith("{\"error\":\"invalid_token\",\"error_description\":\"");
    }

    @Test
    void testHeadersOnlyWhenAllowed() {
        var allowed = permissive.get("/api/whoami", "X-FC-Test-Principal", "prn_test", "X-FC-Test-Scope", "ANCHOR");
        assertThat(allowed.body()).isEqualTo("prn_test:anchor");
        var ignored = strict.get("/api/whoami", "X-FC-Test-Principal", "prn_test", "X-FC-Test-Scope", "ANCHOR");
        assertThat(ignored.body()).isEqualTo("anon");
    }

    @Test
    void sessionCookieIsResolvedThroughTheClaimsResolver() throws Exception {
        var known = mint(keys, Map.of("sub", "prn_cookie"));
        var r = strict.get("/api/whoami", "Cookie", "fc_session=" + known);
        assertThat(r.body()).isEqualTo("prn_cookie:anchor");

        // valid cookie but no principal behind it → unauthenticated, not 401
        var unknown = mint(keys, Map.of("sub", "prn_gone"));
        assertThat(strict.get("/api/whoami", "Cookie", "fc_session=" + unknown).body()).isEqualTo("anon");

        // a stale/garbage cookie degrades to unauthenticated (the SPA re-authenticates)
        var stale = strict.get("/api/whoami", "Cookie", "fc_session=garbage");
        assertThat(stale.statusCode()).isEqualTo(200);
        assertThat(stale.body()).isEqualTo("anon");
    }

    @Test
    void nonBearerAuthorizationHeaderBlocksTheCookieFallback() throws Exception {
        var known = mint(keys, Map.of("sub", "prn_cookie"));
        var r = strict.get("/api/whoami", "Authorization", "Basic abc", "Cookie", "fc_session=" + known);
        assertThat(r.body()).isEqualTo("anon");
    }

    // ── Principal type (docs/spec/portal-apps.md §6, Part A J6) ─────────────

    @Test
    @DisplayName("a session-cookie context always carries PrincipalType.USER, not whatever the resolver left it as")
    void sessionCookieContextCarriesPrincipalTypeUser() throws Exception {
        // Mutant: the Authenticator leaves the resolver's context untouched
        // (session contexts left null-typed) — RESOLVER above never sets a
        // principalType, so this fails unless the Authenticator itself stamps it.
        var known = mint(keys, Map.of("sub", "prn_cookie"));
        assertThat(strict.get("/api/principal-type", "Cookie", "fc_session=" + known).body()).isEqualTo("USER");
    }

    @Test
    @DisplayName("a bearer token's own type claim is preserved verbatim")
    void bearerTokenPreservesItsOwnPrincipalTypeClaim() throws Exception {
        var userToken = mint(keys, Map.of("type", "USER"));
        assertThat(strict.get("/api/principal-type", "Authorization", "Bearer " + userToken).body()).isEqualTo("USER");
        var serviceToken = mint(keys, Map.of("type", "SERVICE"));
        assertThat(strict.get("/api/principal-type", "Authorization", "Bearer " + serviceToken).body()).isEqualTo("SERVICE");
    }

    @Test
    @DisplayName("a test-header context takes its type only from X-FC-Test-Principal-Type, absent otherwise")
    void testHeaderContextTakesPrincipalTypeOnlyFromItsOwnHeader() {
        var noType = permissive.get("/api/principal-type", "X-FC-Test-Principal", "prn_test");
        assertThat(noType.body()).as("absent header -> null -> exempt from the profile-only gate").isEqualTo("null");

        var withType = permissive.get("/api/principal-type", "X-FC-Test-Principal", "prn_test",
                "X-FC-Test-Principal-Type", "USER");
        assertThat(withType.body()).isEqualTo("USER");
    }
}

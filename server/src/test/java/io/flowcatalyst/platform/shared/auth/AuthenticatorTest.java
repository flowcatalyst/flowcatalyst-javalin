package io.flowcatalyst.platform.shared.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
        return new TestHttp(cfg -> {
            cfg.routes.before("/api/*", auth);
            cfg.routes.get("/api/whoami", ctx -> {
                var ac = Auth.from(ctx);
                ctx.result(ac == null ? "anon" : ac.principalId() + (ac.isAnchor() ? ":anchor" : ""));
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
}

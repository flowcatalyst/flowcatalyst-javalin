package io.flowcatalyst.platform.auth.token;

import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserIdentity;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Every token the issuer mints is read back through the server's own
/// [JwtVerifier] (so a mint here is a credential there) and through the raw
/// claim set (so the wire shape of auth-core §3.1–§3.3 is pinned exactly,
/// including what must be absent).
class TokenIssuerTest {

    private static final String ISSUER = "https://fc.example.test";
    // Real time (the verifier uses the system clock for exp/nbf), pinned to
    // the second and set slightly in the past so nbf is never "not yet valid".
    private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(30);
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final TokenIssuer ISSUER_UNDER_TEST =
            new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER), Clock.fixed(NOW, ZoneOffset.UTC));
    private static final JwtVerifier VERIFIER =
            new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));

    private static Principal user() {
        Instant updated = Instant.parse("2026-01-02T03:04:05Z");
        return new Principal("prn_user", PrincipalType.USER, UserScope.PARTNER, "clt_home", null, "Ann", true,
                UserIdentity.of("ann@example.com"), null,
                List.of(new RoleAssignment("hr:manager", RoleAssignment.ADMIN_ASSIGNED, NOW)),
                List.of("clt_a"), List.of("app_1"), false, null, Instant.parse("2025-01-01T00:00:00Z"), updated);
    }

    /// The payload as it is on the wire (JSON), not nimbus's typed view — so
    /// a bare-string `aud` reads as a String and times as epoch seconds.
    private static Map<String, Object> raw(String token) {
        try {
            return SignedJWT.parse(token).getPayload().toJSONObject();
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
    }

    private static String kid(String token) {
        try {
            return SignedJWT.parse(token).getHeader().getKeyID();
        } catch (ParseException e) {
            throw new AssertionError(e);
        }
    }

    private static TokenClaims verified(String token) {
        var v = VERIFIER.verify(token);
        assertThat(v).as("the server's own verifier accepts the mint").isInstanceOf(JwtVerifier.Verified.class);
        return ((JwtVerifier.Verified) v).claims();
    }

    @Test
    void apiAccessTokenCarriesTheAuthorityItWasGivenAndNothingElse() {
        var authority = new TokenIssuer.Authority(List.of("clt_a:acme"), List.of("hr:manager"), List.of("app_1:orders"),
                false, List.of("hr:leave:request:approve", "hr:leave:request:read"));
        String token = ISSUER_UNDER_TEST.accessToken(user(), authority, "oac_rp");

        var claims = verified(token);
        assertThat(claims.subject()).isEqualTo("prn_user");
        assertThat(claims.tokenUse()).isEqualTo(TokenClaims.TOKEN_USE_API);
        assertThat(claims.roles()).containsExactly("hr:manager");
        assertThat(claims.permissions()).containsExactly("hr:leave:request:approve", "hr:leave:request:read");

        var r = raw(token);
        assertThat(r.get("iss")).isEqualTo(ISSUER);
        assertThat(r.get("aud")).as("bare string, never an array").isEqualTo(ISSUER);
        assertThat(r.get("type")).isEqualTo("USER");
        assertThat(r.get("tier")).isEqualTo("PARTNER");
        assertThat(r.get("email")).isEqualTo("ann@example.com");
        assertThat(r.get("name")).isEqualTo("Ann");
        assertThat(r.get("clients")).isEqualTo(List.of("clt_a:acme"));
        assertThat(r.get("applications")).isEqualTo(List.of("app_1:orders"));
        assertThat(r.get("all_applications")).isEqualTo(false);
        assertThat(r.get("scope")).isEqualTo("hr:leave:request:approve hr:leave:request:read");
        assertThat(r.get("azp")).isEqualTo("oac_rp");
        assertThat(r.get("jti")).isNotNull();
        assertThat(r).doesNotContainKey("permissions");
        assertThat(r.get("exp")).isEqualTo(NOW.plusSeconds(3600).getEpochSecond());
        assertThat(r.get("nbf")).isEqualTo(NOW.getEpochSecond());
        assertThat(kid(token)).isEqualTo(KEYS.kid());
    }

    @Test
    void emptyScopeOmitsTheClaimAndNoAzpOmitsThatToo() {
        var authority = new TokenIssuer.Authority(List.of("*"), List.of(), List.of("*"), true, List.of());
        var r = raw(ISSUER_UNDER_TEST.accessToken(user(), authority, null));
        assertThat(r).doesNotContainKey("scope").doesNotContainKey("azp");
        assertThat(r.get("clients")).isEqualTo(List.of("*"));
        assertThat(r.get("all_applications")).isEqualTo(true);
    }

    @Test
    void servicePrincipalsNeverCarryAnEmail() {
        var service = Principal.newService("sa_1", "Robot");
        var r = raw(ISSUER_UNDER_TEST.accessToken(service, new TokenIssuer.Authority(List.of("*"), List.of(), List.of("*"), true, List.of()), null));
        assertThat(r).doesNotContainKey("email");
        assertThat(r.get("type")).isEqualTo("SERVICE");
    }

    @Test
    void identityAccessTokenIsStrippedOfAllAuthority() {
        String token = ISSUER_UNDER_TEST.identityAccessToken(user(), "oac_rp");
        var r = raw(token);
        assertThat(r.get("token_use")).isEqualTo(TokenClaims.TOKEN_USE_IDENTITY);
        assertThat(r.get("clients")).isEqualTo(List.of());
        assertThat(r.get("roles")).isEqualTo(List.of());
        assertThat(r.get("applications")).isEqualTo(List.of());
        assertThat(r.get("all_applications")).isEqualTo(false);
        assertThat(r).doesNotContainKey("scope");
        assertThat(r.get("email")).as("identity fields stay intact").isEqualTo("ann@example.com");
        assertThat(r.get("azp")).isEqualTo("oac_rp");
        // The verifier reads it, and the middleware later refuses it as an API credential.
        assertThat(verified(token).tokenUse()).isEqualTo(TokenClaims.TOKEN_USE_IDENTITY);
    }

    @Test
    void idTokenHasTheOidcShapeWithTheRealAuthTimeAndTheRelyingPartyAsAudience() {
        Instant signedInAt = NOW.minusSeconds(3000);
        var in = new TokenIssuer.IdTokenInput("oac_rp", "n0nce", signedInAt, List.of("orders:admin"),
                List.of("app_1:orders"), false, List.of("clt_a:acme"));
        String token = ISSUER_UNDER_TEST.idToken(user(), in);

        var r = raw(token);
        assertThat(r.get("aud")).isEqualTo("oac_rp");
        assertThat(r.get("azp")).isEqualTo("oac_rp");
        assertThat(r.get("auth_time")).as("when the user signed in, not when this token was minted").isEqualTo(signedInAt.getEpochSecond());
        assertThat(r.get("updated_at")).isEqualTo(Instant.parse("2026-01-02T03:04:05Z").getEpochSecond());
        assertThat(r.get("nonce")).isEqualTo("n0nce");
        assertThat(r.get("email")).isEqualTo("ann@example.com");
        assertThat(r.get("email_verified")).isEqualTo(true);
        assertThat(r.get("client_id")).isEqualTo("clt_home");
        assertThat(r.get("roles")).isEqualTo(List.of("orders:admin"));
        assertThat(r.get("applications")).isEqualTo(List.of("app_1:orders"));
        assertThat(r.get("all_applications")).isEqualTo(false);
        assertThat(r.get("exp")).isEqualTo(NOW.plusSeconds(300).getEpochSecond());
        assertThat(r).doesNotContainKey("nbf").doesNotContainKey("jti")
                .doesNotContainKey("acr").doesNotContainKey("amr").doesNotContainKey("token_use");
        assertThat(kid(token)).isEqualTo(KEYS.kid());
    }

    @Test
    void idTokenWithoutAnAuthTimeFallsBackToNowAndWithoutANonceOmitsIt() {
        var in = new TokenIssuer.IdTokenInput("oac_rp", null, null, List.of(), List.of(), false, List.of());
        var r = raw(ISSUER_UNDER_TEST.idToken(user(), in));
        assertThat(r.get("auth_time")).isEqualTo(NOW.getEpochSecond());
        assertThat(r).doesNotContainKey("nonce");
        assertThat(r.get("roles")).isEqualTo(List.of());
    }

    @Test
    void sessionTokenIsIdentityOnlyWithNoAudienceAndNoKid() {
        String token = ISSUER_UNDER_TEST.sessionToken("prn_user", "ann@example.com");
        var r = raw(token);
        assertThat(r.get("sub")).isEqualTo("prn_user");
        assertThat(r.get("email")).isEqualTo("ann@example.com");
        assertThat(r.get("tier")).isEqualTo("");
        assertThat(r.get("all_applications")).isEqualTo(false);
        assertThat(r).as("no aud is what lets the middleware's audience guard pass cookies")
                .doesNotContainKey("aud").doesNotContainKey("clients").doesNotContainKey("roles")
                .doesNotContainKey("applications").doesNotContainKey("scope").doesNotContainKey("token_use");
        assertThat(r.get("exp")).isEqualTo(NOW.plusSeconds(24 * 3600).getEpochSecond());
        assertThat(kid(token)).as("sessiontoken.Mint stamps no kid").isNull();
        // And the verifier accepts it as a session (no audience → passes).
        assertThat(verified(token).subject()).isEqualTo("prn_user");
    }

    @Test
    void anotherKeysTokenIsRejectedByThisServersVerifier() {
        var other = new TokenIssuer(SigningKeys.generateEphemeral(), TokenIssuer.Config.of(ISSUER));
        String token = other.sessionToken("prn_user", null);
        assertThat(VERIFIER.verify(token)).isInstanceOf(JwtVerifier.Rejected.class);
    }

    @Test
    void fullAuthorityResolvesLabelsForExactlyTheIdsTheClaimsNeed() {
        var labels = new ClaimLabels() {
            @Override
            public Map<String, String> clientIdentifiers(java.util.Collection<String> ids) {
                assertThat(ids).containsExactly("clt_a");
                return Map.of("clt_a", "acme");
            }

            @Override
            public Map<String, String> applicationCodes(java.util.Collection<String> ids) {
                assertThat(ids).containsExactly("app_1");
                return Map.of("app_1", "orders");
            }
        };
        var a = TokenIssuer.Authority.full(user(), List.of("hr:leave:request:read"), labels);
        assertThat(a.clients()).containsExactly("clt_a:acme");
        assertThat(a.applications()).containsExactly("app_1:orders");
        assertThat(a.roles()).containsExactly("hr:manager");
        assertThat(a.scope()).containsExactly("hr:leave:request:read");
        assertThat(a.allApplications()).isFalse();
    }
}

package io.flowcatalyst.platform.serviceaccount.operations;

import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// A minted service-account token must be accepted by this server's own
/// authenticator with the grant the spec describes (§8 step 7) — that is
/// the behaviour a caller depends on, and the reason the mint signs RS256
/// under the platform key rather than anything else.
class RsaServiceAccountTokenMinterTest {

    private static final String ISSUER = "https://fc.test";

    private static Principal servicePrincipal(UserScope scope, String clientId, List<String> apps) {
        return new Principal(EntityType.PRINCIPAL.generate(), PrincipalType.SERVICE, scope, clientId, null,
                "Orders robot", true, null, EntityType.SERVICE_ACCOUNT.generate(),
                List.of(new RoleAssignment("orders:admin", RoleAssignment.SDK_SYNC, Instant.EPOCH)),
                List.of(), apps, false, null, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void aMintedTokenVerifiesUnderThePlatformKeyAndCarriesTheGrant() throws Exception {
        var keys = SigningKeys.generateEphemeral();
        var minter = new RsaServiceAccountTokenMinter(keys, ISSUER, ISSUER);
        var principal = servicePrincipal(UserScope.CLIENT, "cli_home", List.of("app_orders"));

        var minted = minter.mint(principal, List.of("platform:events:create", "platform:events:view"));

        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(keys.publicKey())));
        var verified = verifier.verify(minted.accessToken());
        assertThat(verified).as("the server's own verifier accepts the mint").isInstanceOf(JwtVerifier.Verified.class);
        var claims = ((JwtVerifier.Verified) verified).claims();
        assertThat(claims.subject()).isEqualTo(principal.id());
        assertThat(claims.principalType()).isEqualTo("SERVICE");
        assertThat(claims.tier()).isEqualTo("CLIENT");
        assertThat(claims.clients()).as("a client-scoped principal's home client").containsExactly("cli_home");
        assertThat(claims.roles()).containsExactly("orders:admin");
        assertThat(claims.applications()).containsExactly("app_orders");
        assertThat(claims.allApplications()).isFalse();
        assertThat(claims.permissions()).containsExactly("platform:events:create", "platform:events:view");
        assertThat(claims.tokenUse()).isEqualTo("api");
        assertThat(claims.jti()).isNotBlank();
        assertThat(minted.expiresInSeconds()).isEqualTo(RsaServiceAccountTokenMinter.EXPIRES_IN_SECONDS);

        var header = SignedJWT.parse(minted.accessToken()).getHeader();
        assertThat(header.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(header.getKeyID()).as("kid names the platform key, as Go's mint does").isEqualTo(keys.kid());
    }

    @Test
    void aTokenSignedUnderAnotherKeyIsRejectedByTheVerifier() {
        var platformKeys = SigningKeys.generateEphemeral();
        var otherKeys = SigningKeys.generateEphemeral();
        var minted = new RsaServiceAccountTokenMinter(otherKeys, ISSUER, ISSUER)
                .mint(servicePrincipal(UserScope.CLIENT, "cli_home", List.of()), List.of());

        var verifier = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(platformKeys.publicKey())));
        assertThat(verifier.verify(minted.accessToken())).isInstanceOf(JwtVerifier.Rejected.class);
    }

    @Test
    void theClientsClaimFollowsGoBuildClients() {
        assertThat(RsaServiceAccountTokenMinter.clientsClaim(servicePrincipal(UserScope.ANCHOR, null, List.of())))
                .containsExactly("*");
        var partner = new Principal(EntityType.PRINCIPAL.generate(), PrincipalType.SERVICE, UserScope.PARTNER, null, null,
                "p", true, null, null, List.of(), List.of("cli_a", "cli_b"), List.of(), false, null, Instant.EPOCH, Instant.EPOCH);
        assertThat(RsaServiceAccountTokenMinter.clientsClaim(partner)).containsExactly("cli_a", "cli_b");
        assertThat(RsaServiceAccountTokenMinter.clientsClaim(servicePrincipal(UserScope.CLIENT, null, List.of()))).isEmpty();
    }
}

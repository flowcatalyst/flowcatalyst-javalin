package io.flowcatalyst.platform.serviceaccount.operations;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.shared.auth.ScopeClaim;
import io.flowcatalyst.platform.shared.auth.SigningKeys;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/// Mints the `POST /api/service-accounts/{id}/token` bearer the way the
/// platform's own token service does (Go `authservice.generateTokenWithExpiry`,
/// authoritative = true): **RS256 under the platform signing key**, `kid` in
/// the header, and the full access-token claim set the [io.flowcatalyst.platform.shared.auth.JwtVerifier]
/// reads back — so a token minted here is accepted by this same server's
/// authenticator, which is the whole point of minting one.
///
/// Claims (spec §8 step 7, "the same grant computation as the
/// `client_credentials` path"): `iss`, `aud`, `sub` (the SERVICE principal),
/// `iat`/`nbf`/`exp` (one hour), `jti`, `type`, `tier`, `email`, `name`,
/// `clients` (`["*"]` for an anchor, the assigned clients for a partner, the
/// home client for a client-scoped principal — Go's `buildClients`; the
/// `id:identifier` pair form is not reproduced, the verifier accepts bare
/// ids), `roles`, `applications`, `all_applications`, `scope` (the flattened
/// permissions, space-joined) and `token_use = "api"`.
public final class RsaServiceAccountTokenMinter implements ServiceAccountTokenMinter {

    public static final long EXPIRES_IN_SECONDS = 3600;
    static final String TOKEN_USE_API = "api";

    private final SigningKeys keys;
    private final String issuer;
    private final String audience;

    public RsaServiceAccountTokenMinter(SigningKeys keys, String issuer, String audience) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.audience = audience == null || audience.isBlank() ? issuer : audience;
    }

    @Override
    public Minted mint(Principal principal, List<String> permissions) {
        Objects.requireNonNull(principal, "principal");
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(principal.id())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(EXPIRES_IN_SECONDS)))
                .jwtID(UUID.randomUUID().toString())
                .claim("type", principal.type().name())
                .claim("tier", principal.scope().name())
                .claim("email", principal.email())
                .claim("name", principal.name())
                .claim("clients", clientsClaim(principal))
                .claim("roles", List.copyOf(principal.roleNames()))
                .claim("applications", List.copyOf(principal.accessibleApplicationIds()))
                .claim("all_applications", principal.allApplications())
                .claim("token_use", TOKEN_USE_API);
        if (permissions != null && !permissions.isEmpty()) {
            claims.claim("scope", String.join(" ", permissions));
        }
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.kid()).build();
        var signed = new SignedJWT(header, claims.build());
        try {
            signed.sign(new RSASSASigner(keys.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign service account token", e);
        }
        return new Minted(signed.serialize(), EXPIRES_IN_SECONDS);
    }

    /// Go's `buildClients`: anchor → the wildcard; partner → every assigned
    /// client; client-scoped → the home client when it has one.
    static List<String> clientsClaim(Principal principal) {
        return switch (principal.scope()) {
            case ANCHOR -> List.of(ScopeClaim.WILDCARD);
            case PARTNER -> List.copyOf(principal.assignedClients());
            case CLIENT -> principal.clientId() == null ? List.of() : List.of(principal.clientId());
        };
    }

    @Override
    public String toString() {
        return "RsaServiceAccountTokenMinter[issuer=" + issuer + ", kid=" + keys.kid() + "]";
    }
}

package io.flowcatalyst.platform.auth.oauth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.TokenClaims;

import java.text.ParseException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// Reads a platform access token the way `/oauth/introspect` and
/// `/oauth/userinfo` need it: verified through the server's own
/// [JwtVerifier], plus the claims the verifier's [TokenClaims] does not
/// carry (`azp`, `exp`, `iss`) read off the same signed payload.
public final class AccessTokenReader {

    /// @param claims the verifier's view
    /// @param azp    the OAuth client the token was minted through; `null` when minted outside one
    /// @param issuer `iss`
    /// @param expiresAt `exp`; `null` when absent
    public record Read(TokenClaims claims, String azp, String issuer, Instant expiresAt) {
    }

    private final JwtVerifier verifier;

    public AccessTokenReader(JwtVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    /// Empty when the token is missing, malformed, or fails verification.
    public Optional<Read> read(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (!(verifier.verify(token) instanceof JwtVerifier.Verified v)) {
            return Optional.empty();
        }
        try {
            JWTClaimsSet cs = SignedJWT.parse(token).getJWTClaimsSet();
            String azp = cs.getClaim("azp") instanceof String s && !s.isBlank() ? s : null;
            Instant exp = cs.getExpirationTime() == null ? null : cs.getExpirationTime().toInstant();
            return Optional.of(new Read(v.claims(), azp, cs.getIssuer(), exp));
        } catch (ParseException e) {
            return Optional.empty();
        }
    }

    /// The token after a `Bearer ` prefix (scheme case-insensitive), else `null`.
    public static String bearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        int space = authorizationHeader.indexOf(' ');
        if (space < 0 || !authorizationHeader.substring(0, space).equalsIgnoreCase("Bearer")) {
            return null;
        }
        String token = authorizationHeader.substring(space + 1).trim();
        return token.isEmpty() ? null : token;
    }
}

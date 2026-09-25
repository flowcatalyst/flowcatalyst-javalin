package io.flowcatalyst.platform.shared.auth.jwks;

import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.TokenClaims;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/// A platform-issued bearer JWT verified by a process that is not the
/// platform. Shared by the function host (`auth: platform`, spec
/// `function-host-listener.md` §3) and the router's API
/// (`docs/spec/router-api-auth.md`). The token is
/// verified locally with the server's own [JwtVerifier] over keys from
/// [JwksKeySource] — issuer and expiry checked the same way the platform's
/// own authenticator does, because it IS the same [JwtVerifier].
///
/// The issuer is never a constructor argument (that was the original
/// defect: `FC_FN_PLATFORM_URL`, the address the HOST uses to reach the
/// platform, is not what a token's `iss` carries — the platform's own
/// external base URL is). [JwksKeySource] discovers it lazily; until
/// discovery has succeeded once, every `platform`-auth call is rejected.
public final class BearerAuthenticator {

    private final JwksKeySource keySource;
    private final Clock clock;

    public sealed interface Outcome permits Authenticated, Rejected {
    }

    public record Authenticated(TokenClaims claims) implements Outcome {
        public Authenticated {
            Objects.requireNonNull(claims, "claims");
        }
    }

    public record Rejected(String reason) implements Outcome {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public BearerAuthenticator(JwksKeySource keySource) {
        this(keySource, Clock.systemUTC());
    }

    /// @param clock injectable so a test can fix "now" for expiry checks without sleeping
    public BearerAuthenticator(JwksKeySource keySource, Clock clock) {
        this.keySource = Objects.requireNonNull(keySource, "keySource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// @param authorizationHeader the raw `Authorization` header value, or `null` when absent
    public Outcome authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return new Rejected("missing bearer token");
        }
        String token = authorizationHeader.substring("Bearer ".length());
        String kid;
        try {
            kid = SignedJWT.parse(token).getHeader().getKeyID();
        } catch (ParseException e) {
            return new Rejected("token is malformed: " + e.getMessage());
        }

        // An unknown kid refetches discovery+JWKS at most once per 30 s (spec §3) —
        // JwksKeySource itself owns the floor; a known kid never touches the network.
        keySource.ensureKnown(kid);
        String issuer = keySource.issuer();
        if (issuer == null) {
            return new Rejected("platform issuer could not be discovered");
        }
        List<RSAPublicKey> keys = keySource.keys();
        if (keys.isEmpty()) {
            return new Rejected("no verification keys available");
        }

        JwtVerifier verifier =
                new JwtVerifier(new JwtVerifier.Config(issuer, JwtVerifier.RsaKeys.of(keys)), clock);
        return switch (verifier.verify(token)) {
            // An identity token (issued to relying parties without API access and to portal
            // identities) is not an API credential — the platform's own Authenticator refuses
            // it as a bearer, and so must a function endpoint, or a relying party could replay
            // a user's identity token (tier and all) at an `auth: platform` endpoint.
            case JwtVerifier.Verified(TokenClaims claims) when TokenClaims.TOKEN_USE_IDENTITY.equals(claims.tokenUse()) ->
                    new Rejected("an identity token is not an API credential");
            case JwtVerifier.Verified(TokenClaims claims) -> new Authenticated(claims);
            case JwtVerifier.Rejected(String reason) -> new Rejected(reason);
        };
    }
}

package io.flowcatalyst.platform.shared.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Verifies the platform's JWTs — both the `Authorization: Bearer` access
/// token (authservice) and the `fc_session` cookie (sessiontoken) — and
/// projects the FlowCatalyst claims onto [TokenClaims].
///
/// Rules (Go `sessiontoken.Validate` + `authservice.ValidateToken`):
///
///   - algorithm must be the configured one: RS256 in production, HS256 only
///     when a shared secret is configured for development;
///   - the signature must verify against the **current** key or any
///     **previous** key (validation-only rotation);
///   - `exp` (when present) must be in the future, `nbf` (when present) must
///     not be — no leeway, like golang-jwt's defaults;
///   - `iss` must equal the configured issuer exactly;
///   - a token that carries `aud` must include the configured audience;
///     tokens with **no** `aud` pass (session cookies are minted without one).
///     This is what keeps OIDC ID tokens — signed with the same key, `aud` =
///     a third-party client id — from replaying as platform bearers;
///   - `sub` is required.
///
/// A token that fails a rule is an everyday outcome for an authenticator
/// (expired sessions, stale cookies, foreign issuers), not an exceptional
/// condition, so [#verify] returns a [Verification] the caller switches on
/// rather than throwing.
public final class JwtVerifier {

    /// Verification key material.
    public sealed interface Keys permits RsaKeys, HmacKey {
    }

    /// RS256: the current public key plus zero or more previous keys.
    public record RsaKeys(RSAPublicKey current, List<RSAPublicKey> previous) implements Keys {
        public RsaKeys {
            Objects.requireNonNull(current, "current");
            previous = previous == null ? List.of() : List.copyOf(previous);
        }

        public RsaKeys(RSAPublicKey current) {
            this(current, List.of());
        }
    }

    /// HS256 (development only): the shared secret. Refused when empty.
    public record HmacKey(byte[] secret) implements Keys {
        public HmacKey {
            if (secret == null || secret.length == 0) {
                throw new IllegalArgumentException("HS256 secret must not be empty");
            }
            secret = secret.clone();
        }

        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }

    /// `issuer` is the expected `iss`; `audience` the platform audience
    /// (defaults to the issuer when blank, as Go's provider does).
    public record Config(String issuer, String audience, Keys keys) {
        public Config {
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(keys, "keys");
            if (audience == null || audience.isBlank()) audience = issuer;
        }

        public Config(String issuer, Keys keys) {
            this(issuer, issuer, keys);
        }
    }

    /// The outcome of [#verify]: the claims of a token that passed every rule,
    /// or the reason it did not.
    public sealed interface Verification permits Verified, Rejected {
    }

    public record Verified(TokenClaims claims) implements Verification {
        public Verified {
            Objects.requireNonNull(claims, "claims");
        }
    }

    /// `reason` is what the 401 body's `error_description` carries
    /// (golang-jwt-flavoured: `token is expired`, `token signature is
    /// invalid`, …) — human-readable and free of secrets.
    public record Rejected(String reason) implements Verification {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final Config config;
    private final JWSAlgorithm algorithm;
    private final List<JWSVerifier> verifiers;
    private final Clock clock;

    public JwtVerifier(Config config) {
        this(config, Clock.systemUTC());
    }

    public JwtVerifier(Config config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        var list = new ArrayList<JWSVerifier>();
        switch (config.keys()) {
            case RsaKeys rsa -> {
                algorithm = JWSAlgorithm.RS256;
                list.add(new RSASSAVerifier(rsa.current()));
                for (var prev : rsa.previous()) list.add(new RSASSAVerifier(prev));
            }
            case HmacKey hmac -> {
                algorithm = JWSAlgorithm.HS256;
                try {
                    list.add(new MACVerifier(hmac.secret()));
                } catch (JOSEException e) {
                    throw new IllegalArgumentException("HS256 secret rejected: " + e.getMessage(), e);
                }
            }
        }
        this.verifiers = List.copyOf(list);
    }

    public Config config() {
        return config;
    }

    /// `RS256` or `HS256`.
    public String algorithm() {
        return algorithm.getName();
    }

    /// Checks `token` against every rule above.
    public Verification verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            return new Rejected("token is malformed: " + e.getMessage());
        }
        if (!algorithm.equals(jwt.getHeader().getAlgorithm())) {
            return new Rejected("token signature is invalid: unexpected signing method " + jwt.getHeader().getAlgorithm());
        }
        if (!signatureValid(jwt)) {
            return new Rejected("token signature is invalid");
        }
        JWTClaimsSet cs;
        try {
            cs = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            return new Rejected("token is malformed: " + e.getMessage());
        }
        var now = clock.instant();
        var exp = cs.getExpirationTime();
        if (exp != null && !now.isBefore(exp.toInstant())) {
            return new Rejected("token has invalid claims: token is expired");
        }
        var nbf = cs.getNotBeforeTime();
        if (nbf != null && now.isBefore(nbf.toInstant())) {
            return new Rejected("token has invalid claims: token is not valid yet");
        }
        if (!config.issuer().equals(cs.getIssuer())) {
            return new Rejected("sessiontoken: issuer not accepted");
        }
        var auds = cs.getAudience();
        if (auds != null && !auds.isEmpty() && !auds.contains(config.audience())) {
            return new Rejected("sessiontoken: audience not accepted (not a platform token)");
        }
        var subject = cs.getSubject();
        if (subject == null || subject.isEmpty()) {
            return new Rejected("sessiontoken: token is missing sub claim");
        }
        var scope = stringClaim(cs, "scope");
        var permissions = scope == null || scope.isBlank() ? List.<String>of() : List.of(scope.trim().split("\\s+"));
        var iat = cs.getIssueTime();
        return new Verified(new TokenClaims(
                subject,
                stringClaim(cs, "type"),
                stringClaim(cs, "tier"),
                stringClaim(cs, "email"),
                stringClaim(cs, "name"),
                stringListClaim(cs, "clients"),
                stringListClaim(cs, "roles"),
                stringListClaim(cs, "applications"),
                boolClaim(cs, "all_applications"),
                permissions,
                stringClaim(cs, "token_use"),
                stringClaim(cs, "jti"),
                iat == null ? null : Instant.ofEpochSecond(iat.toInstant().getEpochSecond())));
    }

    private boolean signatureValid(SignedJWT jwt) {
        for (var v : verifiers) {
            try {
                if (jwt.verify(v)) return true;
            } catch (JOSEException _) {
                // try the next key
            }
        }
        return false;
    }

    /// The claim as a string, or `null` when absent / not a string.
    private static String stringClaim(JWTClaimsSet cs, String name) {
        return cs.getClaim(name) instanceof String s ? s : null;
    }

    /// `false` when absent / not a boolean.
    private static boolean boolClaim(JWTClaimsSet cs, String name) {
        return cs.getClaim(name) instanceof Boolean b && b;
    }

    /// The string elements of an array claim; `[]` when absent / not an array.
    private static List<String> stringListClaim(JWTClaimsSet cs, String name) {
        if (!(cs.getClaim(name) instanceof List<?> raw)) return List.of();
        return raw.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}

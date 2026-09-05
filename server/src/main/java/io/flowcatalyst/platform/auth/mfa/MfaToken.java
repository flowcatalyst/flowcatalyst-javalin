package io.flowcatalyst.platform.auth.mfa;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;

/// The pending / enrol JWT that carries a user between the password step
/// and the second factor (`docs/spec/auth-identity.md` §6.3): HS256 under
/// `SHA-256("fc-mfa-token-v1|" ‖ D)` where `D` is the session key's private
/// exponent — stable across instances sharing the key, and structurally
/// unusable as a session cookie because the session verifier accepts RS256
/// only. Purpose-bound: a pending token never opens an enrolment and vice
/// versa.
public final class MfaToken {

    public enum Purpose {
        PENDING("mfa_pending"), ENROLL("mfa_enroll");

        public final String claim;

        Purpose(String claim) {
            this.claim = claim;
        }
    }

    public static final Duration PENDING_TTL = Duration.ofMinutes(10);
    public static final Duration ENROLL_TTL = Duration.ofMinutes(30);

    public record Claims(String subject, Purpose purpose) {
    }

    private final byte[] secret;
    private final String issuer;
    private final Clock clock;

    public MfaToken(RSAPrivateKey sessionKey, String issuer) {
        this(sessionKey, issuer, Clock.systemUTC());
    }

    public MfaToken(RSAPrivateKey sessionKey, String issuer, Clock clock) {
        this.secret = derive(Objects.requireNonNull(sessionKey, "sessionKey"));
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// Go's `key.D.Bytes()`: the unsigned big-endian magnitude, no sign byte.
    static byte[] derive(RSAPrivateKey key) {
        BigInteger d = key.getPrivateExponent();
        byte[] mag = d.toByteArray();
        if (mag.length > 1 && mag[0] == 0) {
            byte[] t = new byte[mag.length - 1];
            System.arraycopy(mag, 1, t, 0, t.length);
            mag = t;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("fc-mfa-token-v1|".getBytes(StandardCharsets.UTF_8));
            md.update(mag);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String mint(String subject, Purpose purpose) {
        return mint(subject, purpose, purpose == Purpose.PENDING ? PENDING_TTL : ENROLL_TTL);
    }

    public String mint(String subject, Purpose purpose, Duration ttl) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("mfatoken: subject is required");
        }
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .claim("prp", purpose.claim)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("mfatoken: sign failed", e);
        }
        return jwt.serialize();
    }

    /// Empty for anything but an HS256 token under this secret, from this
    /// issuer, inside its validity window, with a subject and the wanted
    /// purpose.
    public Optional<Claims> parse(String token, Purpose want) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            if (!jwt.verify(new MACVerifier(secret))) {
                return Optional.empty();
            }
            JWTClaimsSet cs = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            if (!issuer.equals(cs.getIssuer())) {
                return Optional.empty();
            }
            if (cs.getExpirationTime() == null || !now.isBefore(cs.getExpirationTime().toInstant())) {
                return Optional.empty();
            }
            if (cs.getNotBeforeTime() != null && now.isBefore(cs.getNotBeforeTime().toInstant())) {
                return Optional.empty();
            }
            String sub = cs.getSubject();
            if (sub == null || sub.isEmpty()) {
                return Optional.empty();
            }
            if (!(cs.getClaim("prp") instanceof String prp) || !want.claim.equals(prp)) {
                return Optional.empty();
            }
            return Optional.of(new Claims(sub, want));
        } catch (ParseException | JOSEException | IllegalStateException e) {
            return Optional.empty();
        }
    }
}

package io.flowcatalyst.platform.auth.mfa;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-identity.md` §6.3: purpose-bound, short-lived, derived
/// from the session key, and never a session cookie.
class MfaTokenTest {

    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final String ISSUER = "http://localhost:8080";
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

    private static MfaToken at(Instant now) {
        return new MfaToken(KEYS.privateKey(), ISSUER, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void mintsAndParsesForTheSamePurposeOnly() {
        MfaToken t = at(T0);
        String pending = t.mint("prn_1", MfaToken.Purpose.PENDING);
        assertThat(t.parse(pending, MfaToken.Purpose.PENDING)).hasValue(new MfaToken.Claims("prn_1", MfaToken.Purpose.PENDING));
        assertThat(t.parse(pending, MfaToken.Purpose.ENROLL)).as("wrong purpose").isEmpty();
        String enroll = t.mint("prn_1", MfaToken.Purpose.ENROLL);
        assertThat(t.parse(enroll, MfaToken.Purpose.PENDING)).isEmpty();
        assertThat(t.parse(enroll, MfaToken.Purpose.ENROLL)).isPresent();
    }

    @Test
    void pendingLivesTenMinutesAndEnrolThirty() {
        String pending = at(T0).mint("prn_1", MfaToken.Purpose.PENDING);
        String enroll = at(T0).mint("prn_1", MfaToken.Purpose.ENROLL);
        assertThat(at(T0.plus(Duration.ofMinutes(9))).parse(pending, MfaToken.Purpose.PENDING)).isPresent();
        assertThat(at(T0.plus(Duration.ofMinutes(10))).parse(pending, MfaToken.Purpose.PENDING)).as("expired at exp").isEmpty();
        assertThat(at(T0.plus(Duration.ofMinutes(29))).parse(enroll, MfaToken.Purpose.ENROLL)).isPresent();
        assertThat(at(T0.plus(Duration.ofMinutes(30))).parse(enroll, MfaToken.Purpose.ENROLL)).isEmpty();
        assertThat(at(T0.minusSeconds(5)).parse(pending, MfaToken.Purpose.PENDING)).as("before nbf").isEmpty();
    }

    @Test
    void tamperedCrossKeyAndForeignIssuerTokensAreRejected() {
        MfaToken t = at(T0);
        String tok = t.mint("prn_1", MfaToken.Purpose.PENDING);
        String[] parts = tok.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(1) + "A";
        assertThat(t.parse(tampered, MfaToken.Purpose.PENDING)).isEmpty();

        MfaToken otherKey = new MfaToken(SigningKeys.generateEphemeral().privateKey(), ISSUER, Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(otherKey.parse(tok, MfaToken.Purpose.PENDING)).as("another instance's key").isEmpty();

        MfaToken otherIssuer = new MfaToken(KEYS.privateKey(), "http://elsewhere", Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(otherIssuer.parse(tok, MfaToken.Purpose.PENDING)).isEmpty();
        assertThat(t.parse("", MfaToken.Purpose.PENDING)).isEmpty();
        assertThat(t.parse("not.a.jwt", MfaToken.Purpose.PENDING)).isEmpty();
    }

    @Test
    void anMfaTokenIsNotASessionAndASessionIsNotAnMfaToken() throws Exception {
        MfaToken t = at(T0);
        String tok = t.mint("prn_1", MfaToken.Purpose.PENDING);
        var sessions = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())),
                Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(sessions.verify(tok)).as("HS256 never passes the RS256-only session verifier")
                .isInstanceOf(JwtVerifier.Rejected.class);

        // The same claims signed RS256 under the session key: not an MFA token.
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("prn_1").claim("prp", "mfa_pending")
                .issueTime(Date.from(T0)).expirationTime(Date.from(T0.plusSeconds(600))).build();
        var rs = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        rs.sign(new RSASSASigner(KEYS.privateKey()));
        assertThat(t.parse(rs.serialize(), MfaToken.Purpose.PENDING)).isEmpty();
    }

    @Test
    void theSecretIsSha256OfThePrefixAndTheUnsignedPrivateExponent() throws Exception {
        byte[] d = KEYS.privateKey().getPrivateExponent().toByteArray();
        if (d[0] == 0) {
            byte[] t = new byte[d.length - 1];
            System.arraycopy(d, 1, t, 0, t.length);
            d = t;
        }
        var md = MessageDigest.getInstance("SHA-256");
        md.update("fc-mfa-token-v1|".getBytes(StandardCharsets.UTF_8));
        md.update(d);
        assertThat(HexFormat.of().formatHex(MfaToken.derive(KEYS.privateKey()))).isEqualTo(HexFormat.of().formatHex(md.digest()));
    }
}

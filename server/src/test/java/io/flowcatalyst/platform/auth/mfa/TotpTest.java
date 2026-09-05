package io.flowcatalyst.platform.auth.mfa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// RFC 6238 appendix B, SHA-1 column, truncated to the six digits the
/// spec fixes (the appendix prints eight): the secret is the ASCII bytes
/// of `12345678901234567890`.
class TotpTest {

    private static final String SECRET = Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @ParameterizedTest(name = "t={0} → {1}")
    @CsvSource({
            "59, 287082",
            "1111111109, 081804",
            "1111111111, 050471",
            "1234567890, 005924",
            "2000000000, 279037",
            "20000000000, 353130",
    })
    void rfc6238Vectors(long epochSecond, String expected) {
        assertThat(Totp.code(SECRET, Totp.stepOf(Instant.ofEpochSecond(epochSecond)))).isEqualTo(expected);
    }

    @Test
    void oneStepOfSkewEitherSideIsAcceptedAndTwoIsNot() {
        Instant now = Instant.ofEpochSecond(1_111_111_111L);
        long step = Totp.stepOf(now);
        assertThat(Totp.validate(SECRET, Totp.code(SECRET, step - 1), now)).hasValue(step - 1);
        assertThat(Totp.validate(SECRET, Totp.code(SECRET, step), now)).hasValue(step);
        assertThat(Totp.validate(SECRET, Totp.code(SECRET, step + 1), now)).hasValue(step + 1);
        assertThat(Totp.validate(SECRET, Totp.code(SECRET, step - 2), now)).isEmpty();
        assertThat(Totp.validate(SECRET, Totp.code(SECRET, step + 2), now)).isEmpty();
        assertThat(Totp.validate(SECRET, " " + Totp.code(SECRET, step) + " ", now)).as("trimmed").hasValue(step);
        assertThat(Totp.validate(SECRET, "000000", now)).isEmpty();
        assertThat(Totp.validate(SECRET, null, now)).isEmpty();
    }

    @Test
    void theStepAndItsRepresentativeTimeRoundTrip() {
        Instant t = Instant.ofEpochSecond(1_111_111_111L);
        long step = Totp.stepOf(t);
        assertThat(step).isEqualTo(37_037_037L);
        assertThat(Totp.timeForStep(step)).isEqualTo(Instant.ofEpochSecond(37_037_037L * 30));
        assertThat(Totp.stepOf(Totp.timeForStep(step))).isEqualTo(step);
    }

    @Test
    void aFreshSecretIs160BitsOfBase32() {
        String s = Totp.generateSecret();
        assertThat(s).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Base32.decode(s)).hasSize(20);
        assertThat(Totp.generateSecret()).isNotEqualTo(s);
    }

    @Test
    void theOtpauthUriCarriesLabelIssuerAndTheFixedParameters() {
        String uri = Totp.uri("Flow Catalyst", "user@example.com", "ABC234");
        assertThat(uri).isEqualTo("otpauth://totp/Flow%20Catalyst:user@example.com?algorithm=SHA1&digits=6&issuer=Flow+Catalyst&period=30&secret=ABC234");
    }
}

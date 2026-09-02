package io.flowcatalyst.platform.dispatchjob.settled;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [HmacTokenVerifier] (dispatch-seam spec §2, §6, §11): the HKDF-derived
/// secret signs and verifies consistently, a wrong secret or a tampered
/// token/job id never verifies, and a missing key fails closed rather than
/// deriving from nothing.
class HmacTokenVerifierTest {

    @Test
    void aTokenSignedForAJobIdVerifiesUnderTheSameAppKey() {
        var verifier = HmacTokenVerifier.fromAppKey("app-key-material-one");
        String token = verifier.sign("dj_abc123");
        assertThat(token).hasSize(64).matches("[0-9a-f]{64}"); // hex SHA-256
        assertThat(verifier.verify("dj_abc123", token)).isTrue();
    }

    @Test
    void aTokenDoesNotVerifyUnderADifferentAppKey() {
        String token = HmacTokenVerifier.fromAppKey("app-key-material-one").sign("dj_abc123");
        assertThat(HmacTokenVerifier.fromAppKey("app-key-material-two").verify("dj_abc123", token)).isFalse();
    }

    @Test
    void aTokenDoesNotVerifyForADifferentJobId() {
        var verifier = HmacTokenVerifier.fromAppKey("app-key-material-one");
        String token = verifier.sign("dj_abc123");
        assertThat(verifier.verify("dj_other456", token)).isFalse();
    }

    @Test
    void aTamperedTokenDoesNotVerify() {
        var verifier = HmacTokenVerifier.fromAppKey("app-key-material-one");
        String token = verifier.sign("dj_abc123");
        String tampered = (token.charAt(0) == 'a' ? 'b' : 'a') + token.substring(1);
        assertThat(verifier.verify("dj_abc123", tampered)).isFalse();
    }

    @Test
    void aNullTokenNeverVerifies() {
        assertThat(HmacTokenVerifier.fromAppKey("app-key-material-one").verify("dj_abc123", null)).isFalse();
    }

    @Test
    void failsClosedWithNoAppKey() {
        assertThatThrownBy(() -> HmacTokenVerifier.fromAppKey(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacTokenVerifier.fromAppKey("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacTokenVerifier.fromAppKey("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}

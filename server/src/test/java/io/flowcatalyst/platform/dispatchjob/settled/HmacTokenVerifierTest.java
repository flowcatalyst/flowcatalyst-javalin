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

    /// Cross-implementation vector (audit finding, test-gap): every other
    /// test here is Java-vs-Java — this pins [#sign] against a value computed
    /// OUTSIDE the JVM, so a JDK `KDF`/HKDF regression or a subtle spec
    /// misreading (wrong salt, wrong info-string encoding, wrong expand
    /// length) cannot silently agree with itself.
    ///
    /// Computed with OpenSSL 3.x on this machine — `openssl kdf` was
    /// available, so Python's hashlib/hmac fallback (documented in the audit
    /// prompt) was not needed:
    ///
    /// ```
    /// openssl kdf -keylen 32 -kdfopt digest:SHA256 -kdfopt key:test-app-key-0001 \
    ///     -kdfopt info:fc-dispatch-auth -binary HKDF | xxd -p -c 256
    /// # => 11336641817792b0a402aa6d840d931c528a5a4bf36d4628971bc2e1d6888640
    ///
    /// printf '%s' '0HZXA7B2C3D4E5F6G' | openssl dgst -sha256 -mac HMAC \
    ///     -macopt hexkey:11336641817792b0a402aa6d840d931c528a5a4bf36d4628971bc2e1d6888640
    /// # => 8ca74dc303f9775200798b397488d07085e094c912caa8b400e017b2d88e83a4
    /// ```
    ///
    /// (No salt was passed to `openssl kdf`, matching
    /// `HKDFParameterSpec.ofExtract()` never calling `addSalt` — RFC 5869's
    /// default of `HashLen` zero bytes; HMAC zero-pads any key shorter than
    /// its block size to the same result whether the key is empty or
    /// explicitly all-zero, so the two conventions agree here regardless.)
    @Test
    void signMatchesAVectorComputedOutsideTheJvmWithOpenssl() {
        var verifier = HmacTokenVerifier.fromAppKey("test-app-key-0001");
        assertThat(verifier.sign("0HZXA7B2C3D4E5F6G"))
                .isEqualTo("8ca74dc303f9775200798b397488d07085e094c912caa8b400e017b2d88e83a4");
    }
}

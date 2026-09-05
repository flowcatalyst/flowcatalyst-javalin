package io.flowcatalyst.platform.auth.mfa;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCodesTest {

    @Test
    void codesAreTwoGroupsOfFiveOverTheAmbiguityFreeAlphabet() {
        List<String> codes = RecoveryCodes.generate(10);
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        for (String c : codes) {
            assertThat(c).matches("[ABCDEFGHJKMNPQRSTVWXYZ23456789]{5}-[ABCDEFGHJKMNPQRSTVWXYZ23456789]{5}");
        }
        assertThat(RecoveryCodes.ALPHABET).doesNotContain("I", "L", "O", "U", "0", "1").hasSize(30);
    }

    @Test
    void normalisationMakesTheSpacedLowerCaseFormTheSameCode() {
        assertThat(RecoveryCodes.normalize("  a7k2m 9pqrt ")).isEqualTo("A7K2M9PQRT");
        assertThat(RecoveryCodes.normalize("A7K2M-9PQRT")).isEqualTo("A7K2M9PQRT");
        assertThat(RecoveryCodes.hash("  a7k2m 9pqrt ")).isEqualTo(RecoveryCodes.hash("A7K2M-9PQRT"));
        assertThat(RecoveryCodes.hash("A7K2M-9PQRT")).isNotEqualTo(RecoveryCodes.hash("A7K2M-9PQRS"));
        assertThat(RecoveryCodes.normalize(null)).isEmpty();
    }

    @Test
    void theHashIsLowerHexSha256OfTheNormalisedForm() {
        // SHA-256("A7K2M9PQRT")
        assertThat(RecoveryCodes.hash("a7k2m-9pqrt")).isEqualTo(RecoveryCodes.sha256Hex("A7K2M9PQRT")).matches("[0-9a-f]{64}");
        assertThat(RecoveryCodes.sha256Hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void pinsAreZeroPaddedDigits() {
        for (int i = 0; i < 50; i++) {
            assertThat(RecoveryCodes.randomDigits(6)).matches("[0-9]{6}");
        }
        assertThat(RecoveryCodes.randomDigits(1)).hasSize(1);
    }
}

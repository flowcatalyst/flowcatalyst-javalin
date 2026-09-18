package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DigestTest {

    @Test
    void validDigestAccepted() {
        String raw = "sha256:" + "a".repeat(64);
        assertThat(Digest.parse(raw).value()).isEqualTo(raw);
    }

    @Test
    void upperCaseNotFolded() {
        assertRejected("sha256:" + "A".repeat(64));
    }

    @Test
    void tooShortRejected() {
        assertRejected("sha256:" + "a".repeat(63));
    }

    @Test
    void tooLongRejected() {
        assertRejected("sha256:" + "a".repeat(65));
    }

    @Test
    void wrongAlgorithmRejected() {
        assertRejected("sha1:" + "a".repeat(40));
    }

    @ParameterizedTest(name = "[{index}] rejected: {0}")
    @ValueSource(strings = {"deadbeef", "sha256deadbeef"})
    void missingPrefixRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void absentRejected(String raw) {
        assertRejected(raw);
    }

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> Digest.parse(raw))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("DIGEST_INVALID");
                });
    }
}

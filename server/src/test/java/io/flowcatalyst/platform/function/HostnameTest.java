package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §5.1: every row of the Accepted/Rejected table.
class HostnameTest {

    @Test
    void caseIsLowered() {
        assertThat(Hostname.parse("API.Acme.com").value()).isEqualTo("api.acme.com");
    }

    @ParameterizedTest(name = "[{index}] labels accepted: {0}")
    @CsvSource({"a.io", "x-1.acme.co.za"})
    void labelsAccepted(String raw) {
        assertThat(Hostname.parse(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest(name = "[{index}] labels rejected: {0}")
    @CsvSource({"localhost", "a..com", "-a.com", "a_b.com"})
    void labelsRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest(name = "[{index}] form rejected: {0}")
    @CsvSource({"acme.com.", "'*.acme.com'", "acme.com:443", "https://acme.com", "10.0.0.1"})
    void formRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void lengthTwoFiftyThreeAccepted() {
        // 3 labels of 63 + 3 dots (192) + a 61-char label = 253
        String label63 = "a".repeat(63);
        String raw = label63 + "." + label63 + "." + label63 + "." + "a".repeat(61);
        assertThat(raw).hasSize(253);
        assertThat(Hostname.parse(raw).value()).isEqualTo(raw);
    }

    @Test
    void lengthTwoFiftyFourRejected() {
        String label63 = "a".repeat(63);
        String raw = label63 + "." + label63 + "." + label63 + "." + "a".repeat(62);
        assertThat(raw).hasSize(254);
        assertRejected(raw);
    }

    @Test
    void sixtyFourCharLabelRejected() {
        assertRejected("a".repeat(64) + ".com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void absentRejected(String raw) {
        assertRejected(raw);
    }

    // ── #zoneCandidates (spec `function-zones-and-aliases.md` §1) ────────────

    @Test
    void zoneCandidatesWalksUpToTwoLabelsMostSpecificFirst() {
        assertThat(Hostname.parse("qa-myapp.acme.com").zoneCandidates())
                .as("mutant: stop one label too early/late, or wrong order")
                .containsExactly("qa-myapp.acme.com", "acme.com");
    }

    @Test
    void zoneCandidatesOfATwoLabelHostnameIsJustItself() {
        assertThat(Hostname.parse("acme.com").zoneCandidates()).containsExactly("acme.com");
    }

    @Test
    void zoneCandidatesOfADeeperHostnameWalksEveryLevel() {
        assertThat(Hostname.parse("a.b.c.acme.com").zoneCandidates())
                .containsExactly("a.b.c.acme.com", "b.c.acme.com", "c.acme.com", "acme.com");
    }

    private static void assertRejected(String raw) {
        assertThatThrownBy(() -> Hostname.parse(raw))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("HOSTNAME_INVALID");
                });
    }
}

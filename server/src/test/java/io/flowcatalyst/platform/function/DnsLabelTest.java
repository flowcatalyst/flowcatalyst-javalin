package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `function-registry.md` §3.1: every row of the Accepted/Rejected table.
class DnsLabelTest {

    @ParameterizedTest(name = "[{index}] charset: {0}")
    @CsvSource({"billing", "a", "0", "inv-2", "9lives"})
    void charsetAccepted(String raw) {
        assertThat(DnsLabel.parse("field", raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest(name = "[{index}] charset rejects ''{0}''")
    @CsvSource({"Billing", "in_voices", "a.b", "'a b'", "é"})
    void charsetRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void lengthSixtyThreeAccepted() {
        String raw = "a".repeat(63);
        assertThat(DnsLabel.parse("field", raw).value()).isEqualTo(raw);
    }

    @Test
    void emptyIsRejected() {
        assertRejected("");
    }

    @Test
    void lengthSixtyFourRejected() {
        assertRejected("a".repeat(64));
    }

    @ParameterizedTest(name = "[{index}] hyphen edges accepted: {0}")
    @CsvSource({"a-b", "a--b"})
    void hyphenEdgesAccepted(String raw) {
        assertThat(DnsLabel.parse("field", raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest(name = "[{index}] hyphen edges rejected: {0}")
    @CsvSource({"-a", "a-", "-"})
    void hyphenEdgesRejected(String raw) {
        assertRejected(raw);
    }

    @ParameterizedTest(name = "[{index}] whitespace not trimmed: ''{0}''")
    @CsvSource({"' a'", "'a '"})
    void whitespaceRejected(String raw) {
        assertRejected(raw);
    }

    @Test
    void absentIsRejected() {
        assertRejected(null);
    }

    @Test
    void messageNamesTheField() {
        assertThatThrownBy(() -> DnsLabel.parse("application", "Bad_Label"))
                .isInstanceOf(UseCaseException.class)
                .hasMessageContaining("application must be a DNS label");
    }

    private static void assertRejected(String raw) {
        ThrowingCallable call = () -> DnsLabel.parse("field", raw);
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("LABEL_INVALID");
                });
    }
}

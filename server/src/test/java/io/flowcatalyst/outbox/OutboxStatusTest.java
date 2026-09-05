package io.flowcatalyst.outbox;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [OutboxStatus] — the code/retryable/terminal table (spec §2) and the
/// wire item-status parser (spec §6).
class OutboxStatusTest {

    @ParameterizedTest(name = "{0} code={1} retryable={2} terminal={3}")
    @CsvSource({
            "PENDING, 0, false, false",
            "SUCCESS, 1, false, true",
            "BAD_REQUEST, 2, false, true",
            "INTERNAL_ERROR, 3, true, false",
            "UNAUTHORIZED, 4, true, false",
            "FORBIDDEN, 5, false, true",
            "GATEWAY_ERROR, 6, true, false",
            "IN_PROGRESS, 9, true, false",
    })
    void codeRetryableAndTerminalMatchTheSpecTable(OutboxStatus status, int code, boolean retryable, boolean terminal) {
        assertThat(status.code()).isEqualTo(code);
        assertThat(status.retryable()).isEqualTo(retryable);
        assertThat(status.terminal()).isEqualTo(terminal);
    }

    @ParameterizedTest(name = "wire \"{0}\" -> {1}")
    @CsvSource({
            "SUCCESS, SUCCESS",
            "SKIPPED, SUCCESS",
            "BAD_REQUEST, BAD_REQUEST",
            "INTERNAL_ERROR, INTERNAL_ERROR",
            "UNAUTHORIZED, UNAUTHORIZED",
            "FORBIDDEN, FORBIDDEN",
            "GATEWAY_ERROR, GATEWAY_ERROR",
    })
    void parseReadsEveryWireStatusIncludingSkippedAsSuccess(String wire, OutboxStatus expected) {
        assertThat(OutboxStatus.parse(wire)).contains(expected);
    }

    @ParameterizedTest(name = "rejected: \"{0}\"")
    @CsvSource({
            "PENDING",
            "IN_PROGRESS",
            "success",
            "TOTALLY_UNKNOWN",
    })
    void parseRejectsStoredOnlyAndUnrecognisedValuesRatherThanGuessing(String wire) {
        assertThat(OutboxStatus.parse(wire)).as("never a silent default").isEmpty();
    }

    @Test
    void parseRejectsEmptyAndNullRatherThanThrowing() {
        assertThat(OutboxStatus.parse("")).isEmpty();
        assertThat(OutboxStatus.parse(null)).isEmpty();
    }
}

package io.flowcatalyst.platform.shared.dispatch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// [DispatchMode]'s lenient reader and ordering predicate — the one shared
/// enum the wire (`router.wire.Message`), the dispatch-job column and the
/// subscription setting all use (ledger `X-01`, merged 2026-09-02; absorbs
/// what used to be two separate suites, `router.wire.DispatchModeTest`
/// (never actually written — pinned inline in `MessageWireTest`) and
/// `platform.subscription.DispatchModeTest`).
///
/// The default is [DispatchMode#NEXT_ON_ERROR], never `IMMEDIATE`
/// (`X-01`/`A-09`): `IMMEDIATE` is the only mode with no ordering at all, so
/// an absent or malformed value must never silently land there.
class DispatchModeTest {

    // ── Accepted spellings, absence, and unrecognised values ────────────────

    @ParameterizedTest(name = "mode ''{0}'' reads as {1}")
    @CsvSource(nullValues = "NULL", value = {
            // Accepted spellings: the exact constant name, case-sensitive.
            "IMMEDIATE, IMMEDIATE",
            "NEXT_ON_ERROR, NEXT_ON_ERROR",
            "BLOCK_ON_ERROR, BLOCK_ON_ERROR",
            // Absence: no value at all, or blank/whitespace-only.
            "NULL, NEXT_ON_ERROR",
            "'', NEXT_ON_ERROR",
            "'  ', NEXT_ON_ERROR",
            // Unrecognised: wrong case, wrong separator, or simply unknown —
            // all fold to the safe default, never to IMMEDIATE.
            "immediate, NEXT_ON_ERROR",
            "next_on_error, NEXT_ON_ERROR",
            "NEXT-ON-ERROR, NEXT_ON_ERROR",
            "bogus, NEXT_ON_ERROR",
            "SOMETHING_NEW, NEXT_ON_ERROR"})
    void acceptedSpellingsParseExactlyEverythingElseDefaultsToTheOrderingSafeMode(String stored, DispatchMode expected) {
        assertThat(DispatchMode.parse(stored)).isEqualTo(expected);
    }

    @Test
    void anUnrecognisedValueIsLoggedAtWarnButAbsenceIsSilent() {
        var log = (Logger) LoggerFactory.getLogger(DispatchMode.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            assertThat(DispatchMode.parse(null)).isEqualTo(DispatchMode.NEXT_ON_ERROR);
            assertThat(DispatchMode.parse("")).isEqualTo(DispatchMode.NEXT_ON_ERROR);
            assertThat(captured.list).as("absence is not a producer bug; it must not warn").isEmpty();

            assertThat(DispatchMode.parse("bogus")).isEqualTo(DispatchMode.NEXT_ON_ERROR);

            assertThat(captured.list)
                    .as("an unrecognised value IS a producer bug and must be visible")
                    .hasSize(1)
                    .allSatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.WARN);
                        assertThat(e.getFormattedMessage()).contains("bogus").contains("NEXT_ON_ERROR");
                    });
        } finally {
            log.detachAppender(captured);
        }
    }

    // ── requiresOrdering ─────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} requires ordering = {1}")
    @CsvSource({"IMMEDIATE, false", "NEXT_ON_ERROR, true", "BLOCK_ON_ERROR, true"})
    void onlyTheOrderedModesRequireOrdering(DispatchMode mode, boolean requiresOrdering) {
        // Asserting the predicate the pool actually branches on, not just
        // that the field holds some value — a regression that populated
        // dispatchMode with something parsing to IMMEDIATE would still pass
        // a bare field-equality check.
        assertThat(mode.requiresOrdering()).isEqualTo(requiresOrdering);
    }

    // ── Wire round trip ──────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} writes back as its own name")
    @CsvSource({"IMMEDIATE", "NEXT_ON_ERROR", "BLOCK_ON_ERROR"})
    void wireValueIsTheConstantName(DispatchMode mode) {
        assertThat(mode.wireValue()).isEqualTo(mode.name());
    }
}

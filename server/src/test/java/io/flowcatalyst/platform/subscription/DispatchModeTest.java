package io.flowcatalyst.platform.subscription;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// [DispatchMode#parse]'s default (dispatch-seam spec §2 "`dispatchMode`
/// resolution"; X-01/A-09): absent or unrecognised MUST fall back to
/// `NEXT_ON_ERROR`, never `IMMEDIATE` — `IMMEDIATE` is the only mode with no
/// ordering at all, so defaulting to it would silently drop a group's FIFO
/// guarantee. Pinned separately from [SubscriptionTest] because this is the
/// one behaviour this unit's ruling touched.
class DispatchModeTest {

    @ParameterizedTest(name = "mode ''{0}'' reads as {1}")
    @CsvSource(nullValues = "NULL", value = {
            "IMMEDIATE, IMMEDIATE",
            "NEXT_ON_ERROR, NEXT_ON_ERROR",
            "BLOCK_ON_ERROR, BLOCK_ON_ERROR",
            "NULL, NEXT_ON_ERROR",
            "'', NEXT_ON_ERROR",
            "immediate, NEXT_ON_ERROR",
            "bogus, NEXT_ON_ERROR"})
    void unrecognisedAndAbsentFallBackToTheOrderingSafeDefault(String stored, DispatchMode expected) {
        assertThat(DispatchMode.parse(stored)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} requires ordering = {1}")
    @CsvSource({"IMMEDIATE, false", "NEXT_ON_ERROR, true", "BLOCK_ON_ERROR, true"})
    void onlyTheOrderedModesRequireOrdering(DispatchMode mode, boolean requiresOrdering) {
        assertThat(mode.requiresOrdering()).isEqualTo(requiresOrdering);
    }
}

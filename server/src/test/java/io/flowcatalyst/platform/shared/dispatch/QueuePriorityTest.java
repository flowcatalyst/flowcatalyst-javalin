package io.flowcatalyst.platform.shared.dispatch;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [QueuePriority#parse]'s rules (ruling R1/R1a): case-insensitive matching
/// of the two recognised names, absence/blank as "not set", and rejection
/// of anything else — no database involved.
class QueuePriorityTest {

    /// R1a is the load-bearing rule: the shipped SPA sends `queue: "default"`
    /// (lower-case) on every create, and it cannot be changed independently
    /// of the Go frontend it is embedded from. A case-sensitive parser here
    /// would 400 every UI-created subscription.
    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            "default, DEFAULT",
            "DEFAULT, DEFAULT",
            "Default, DEFAULT",
            "  default  , DEFAULT",
            "high_priority, HIGH_PRIORITY",
            "HIGH_PRIORITY, HIGH_PRIORITY",
            "High_Priority, HIGH_PRIORITY",
            "  HIGH_PRIORITY  , HIGH_PRIORITY"})
    void parseIsCaseInsensitiveAndTrims(String raw, QueuePriority expected) {
        assertThat(QueuePriority.parse(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void parseTreatsAbsentOrBlankAsNotSet(String raw) {
        assertThat(QueuePriority.parse(raw)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"workers-high", "low", "high-priority", "defaults", "HIGHPRIORITY"})
    void parseRejectsAnyOtherNonBlankValue(String raw) {
        assertUseCaseError(() -> QueuePriority.parse(raw), UseCaseError.Validation.class, "INVALID_QUEUE");
    }

    @Test
    void theTwoConstantNamesAreTheStoredAndWireForm() {
        assertThat(QueuePriority.DEFAULT.name()).isEqualTo("DEFAULT");
        assertThat(QueuePriority.HIGH_PRIORITY.name()).isEqualTo("HIGH_PRIORITY");
    }

    /// Ruling R6, and the reason [QueuePriority#forPublishing] exists separately
    /// from [QueuePriority#parse]: write validation cannot reach rows that
    /// already exist. `workers-high` is not hypothetical — it is the shape of
    /// the Integral queue segments staging actually holds. If the publish path
    /// reused the throwing `parse`, the scheduler would raise a validation
    /// exception mid-claim and strand every job on that subscription instead of
    /// routing it to the normal lane.
    @ParameterizedTest(name = "stored \"{0}\" publishes to DEFAULT")
    @ValueSource(strings = {"workers-high", "low", "high-priority", "defaults", "HIGHPRIORITY", "   ", "default", "DEFAULT"})
    void forPublishingSendsAnythingUnrecognisedToDefaultAndNeverThrows(String stored) {
        assertThat(QueuePriority.forPublishing(stored)).isEqualTo(QueuePriority.DEFAULT);
    }

    @Test
    void forPublishingTreatsAnUnsetColumnAsDefault() {
        assertThat(QueuePriority.forPublishing(null)).isEqualTo(QueuePriority.DEFAULT);
    }

    /// The one value that must *not* be swallowed by R6's leniency — otherwise
    /// the high-priority lane would never receive anything and the whole
    /// two-queue design would be silently inert.
    @ParameterizedTest
    @ValueSource(strings = {"HIGH_PRIORITY", "high_priority", "  High_Priority  "})
    void forPublishingStillRoutesTheHighPriorityLane(String stored) {
        assertThat(QueuePriority.forPublishing(stored)).isEqualTo(QueuePriority.HIGH_PRIORITY);
    }

    // ── forJob (ruling R4, dispatch-job-priority spec) ──────────────────────

    /// [QueuePriority#forJob] distinguishes "the job named a recognised
    /// priority" from "the job said nothing" — unlike [#forPublishing], it
    /// must report [Optional#empty()] rather than silently defaulting, so
    /// [io.flowcatalyst.platform.scheduler.DispatchDestinationResolver] knows
    /// to fall through to the subscription.
    @Test
    void forJobIsEmptyForNilBlankOrUnrecognisedLegacyText() {
        assertThat(QueuePriority.forJob(null)).isEmpty();
        assertThat(QueuePriority.forJob("  ")).isEmpty();
        assertThat(QueuePriority.forJob("workers-high")).isEmpty();
    }

    @ParameterizedTest(name = "forJob(\"{0}\") = {1}")
    @CsvSource({
            "DEFAULT, DEFAULT",
            "Default, DEFAULT",
            "HIGH_PRIORITY, HIGH_PRIORITY",
            "high_priority, HIGH_PRIORITY"})
    void forJobRecognisesBothNamesCaseInsensitively(String stored, QueuePriority expected) {
        assertThat(QueuePriority.forJob(stored)).contains(expected);
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }
}

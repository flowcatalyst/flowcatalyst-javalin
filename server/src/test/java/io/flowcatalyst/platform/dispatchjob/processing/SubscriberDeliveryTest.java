package io.flowcatalyst.platform.dispatchjob.processing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/// [SubscriberDelivery#attemptTimeout] in isolation (dispatch-seam spec §3's
/// timing table): a pure function, so the clamp and default are pinned
/// directly rather than by driving a real (and possibly hanging) HTTP call
/// through [ProcessingApiTest].
class SubscriberDeliveryTest {

    @Test
    void aJobWithNoTimeoutGetsTheThirtySecondDefault() {
        assertThat(SubscriberDelivery.attemptTimeout(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(SubscriberDelivery.attemptTimeout(-1)).as("a non-positive stored value is treated as absent")
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aJobsOwnTimeoutIsHonouredBelowTheCeiling() {
        assertThat(SubscriberDelivery.attemptTimeout(45)).isEqualTo(Duration.ofSeconds(45));
    }

    /// Audit finding (test-gap): nothing pinned that an operator-set
    /// `timeout_seconds` above the 2-minute outer client ceiling is actually
    /// clamped, not honoured verbatim.
    @Test
    void aTimeoutAboveTheCeilingClampsToTwoMinutes() {
        assertThat(SubscriberDelivery.attemptTimeout(100_000))
                .as("100,000s would otherwise ask the HTTP client to wait over a day for one attempt")
                .isEqualTo(SubscriberDelivery.MAX_TIMEOUT)
                .isEqualTo(Duration.ofMinutes(2));
    }
}

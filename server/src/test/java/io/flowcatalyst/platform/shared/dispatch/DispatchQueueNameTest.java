package io.flowcatalyst.platform.shared.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [DispatchQueueName#compose]: settled item 1's naming convention
/// (`docs/go-mirror/2026-09-12-dispatch-rulings.md`).
class DispatchQueueNameTest {

    /// Pins: the priority segment survives verbatim, underscore included —
    /// the settled examples are literally `FC-staging-acme-DEFAULT.fifo` and
    /// `FC-staging-acme-HIGH_PRIORITY.fifo`. A mutant that sanitises the
    /// whole composed name (not just the tenant) would turn the underscore
    /// into a hyphen and fail this assertion.
    @Test
    void composesTheSettledExampleWithTheUnderscoreSurviving() {
        var name = DispatchQueueName.compose("FC-staging", "acme", QueuePriority.HIGH_PRIORITY, true);

        assertThat(name.value()).isEqualTo("FC-staging-acme-HIGH_PRIORITY.fifo");
    }

    /// Pins R5: a client-less job's tenant is the literal `platform`.
    @Test
    void clientLessTenantIsPlatform() {
        var name = DispatchQueueName.compose("FC-staging", "platform", QueuePriority.DEFAULT, true);

        assertThat(name.value()).isEqualTo("FC-staging-platform-DEFAULT.fifo");
    }

    /// Pins the tenant sanitisation rule: `_`, `.` and space each become
    /// `-`. Defensive (a conforming identifier is already a slug), but
    /// tested directly against a tenant string carrying all three.
    @Test
    void sanitisesUnderscoreDotAndSpaceInTheTenantOnly() {
        var name = DispatchQueueName.compose("FC-staging", "acme_corp.test client", QueuePriority.DEFAULT, true);

        assertThat(name.value()).isEqualTo("FC-staging-acme-corp-test-client-DEFAULT.fifo");
    }

    /// Postgres names get no `.fifo` suffix and no length cap.
    @Test
    void postgresNameHasNoFifoSuffix() {
        var name = DispatchQueueName.compose("FC-staging", "acme", QueuePriority.DEFAULT, false);

        assertThat(name.value()).isEqualTo("FC-staging-acme-DEFAULT");
    }

    /// Pins: an identifier long enough to push the composed SQS name past 80
    /// characters is refused rather than silently truncated. The tenant here
    /// is 90 characters — well past `tnt_clients.identifier`'s `varchar(100)`
    /// ceiling being reachable in practice — and the resulting name would be
    /// well over 80 characters with the fixed `FC-staging-...-DEFAULT.fifo`
    /// segments added.
    @Test
    void refusesAnSqsNameOverEightyCharacters() {
        String longTenant = "a".repeat(90);

        assertThatThrownBy(() -> DispatchQueueName.compose("FC-staging", longTenant, QueuePriority.DEFAULT, true))
                .isInstanceOf(DispatchQueueName.QueueNameTooLongException.class)
                .hasMessageContaining("80")
                .extracting(e -> ((DispatchQueueName.QueueNameTooLongException) e).tenant())
                .isEqualTo(longTenant);
    }

    /// The same over-long tenant composed for Postgres (no `.fifo`, no cap)
    /// succeeds — the length limit is an SQS-specific constraint, not a
    /// general one. This is the counter-check that the exception above is
    /// actually about the 80-char SQS cap, not merely "long tenants fail".
    @Test
    void theSameOverLongTenantIsFineForPostgres() {
        String longTenant = "a".repeat(90);

        var name = DispatchQueueName.compose("FC-staging", longTenant, QueuePriority.DEFAULT, false);

        assertThat(name.value()).startsWith("FC-staging-" + longTenant);
    }

    @Test
    void refusesABlankTenant() {
        assertThatThrownBy(() -> DispatchQueueName.compose("FC-staging", "", QueuePriority.DEFAULT, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DispatchQueueName.compose("FC-staging", "   ", QueuePriority.DEFAULT, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// A blank prefix is tolerated (the SQS-must-have-a-prefix requirement
    /// is enforced once, at startup, by `DispatchQueueSettings#resolve`, not
    /// here) — it simply omits that segment rather than leaving a stray
    /// leading hyphen.
    @Test
    void blankPrefixOmitsTheLeadingSegmentRatherThanRefusing() {
        var name = DispatchQueueName.compose("", "acme", QueuePriority.DEFAULT, false);

        assertThat(name.value()).isEqualTo("acme-DEFAULT");
    }
}

package io.flowcatalyst.router.observability;

import io.flowcatalyst.router.observability.Warnings.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` §2.7, §9.4, §9.5, constant 45.
class WarningStoreTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final WarningStore store = new WarningStore(clock);

    @Test
    @DisplayName("constants match the spec table (constant 45): 8h max age, 1000 cap")
    void constantsMatchSpec() {
        assertThat(WarningStore.MAX_WARNING_AGE).isEqualTo(Duration.ofHours(8));
        assertThat(WarningStore.MAX_WARNINGS).isEqualTo(1000);
    }

    @Test
    @DisplayName("raise() stores a warning with the given severity/category/message, unacknowledged, timestamped from the clock")
    void raiseStoresANotice() {
        store.raise(Severity.WARNING, "POOL_CAPACITY", "all pools full");

        var all = store.snapshot().warnings();
        assertThat(all).hasSize(1);
        var w = all.get(0);
        assertThat(w.severity()).isEqualTo(Severity.WARNING);
        assertThat(w.category()).isEqualTo("POOL_CAPACITY");
        assertThat(w.message()).isEqualTo("all pools full");
        assertThat(w.acknowledged()).isFalse();
        assertThat(w.acknowledgedAt()).isNull();
        assertThat(w.createdAt()).isEqualTo(clock.instant());
        assertThat(w.id()).isNotNull();
    }

    @Test
    @DisplayName("acknowledge(id) flips the flag and stamps acknowledgedAt from the clock at ack time")
    void acknowledgeFlipsFlag() {
        store.raise(Severity.ERROR, "ROUTING", "unknown pool code");
        var id = store.snapshot().warnings().get(0).id();

        clock.advance(Duration.ofMinutes(3));
        assertThat(store.acknowledge(id)).isTrue();

        var w = store.snapshot().warnings().get(0);
        assertThat(w.acknowledged()).isTrue();
        assertThat(w.acknowledgedAt()).isEqualTo(Instant.parse("2026-01-01T00:03:00Z"));
    }

    @Test
    @DisplayName("acknowledge() on an unknown id returns false and changes nothing")
    void acknowledgeUnknownIdReturnsFalse() {
        assertThat(store.acknowledge(UUID.randomUUID())).isFalse();
        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("unacknowledged() returns unacked warnings of ANY age, distinct from active()")
    void unacknowledgedIsAgeless() {
        store.raise(Severity.WARNING, "STALL", "old one");
        var id = store.snapshot().warnings().get(0).id();

        // Well past the 30-minute window health uses for "active", but
        // unacknowledged() must still surface it: it is not age-bounded.
        clock.advance(Duration.ofHours(1));
        store.raise(Severity.INFO, "STALL", "fresh one");

        assertThat(store.unacknowledged()).hasSize(2);
        assertThat(store.active(Duration.ofMinutes(30))).hasSize(1); // only the fresh one

        // Acknowledging removes a warning from unacknowledged() regardless
        // of age.
        store.acknowledge(id);
        assertThat(store.unacknowledged()).hasSize(1);
    }

    @Test
    @DisplayName("active(maxAge) is unacked AND no older than maxAge — a warning at exactly the boundary still counts")
    void activeIncludesExactBoundary() {
        store.raise(Severity.WARNING, "QUEUE_HEALTH", "backlog growing");

        clock.advance(Duration.ofMinutes(30)); // exactly at the boundary

        assertThat(store.active(Duration.ofMinutes(30)))
                .as("a warning exactly maxAge old is still <= maxAge")
                .hasSize(1);
    }

    @Test
    @DisplayName("active(maxAge) excludes a warning one minute past the boundary")
    void activeExcludesJustPastBoundary() {
        store.raise(Severity.WARNING, "QUEUE_HEALTH", "backlog growing");

        clock.advance(Duration.ofMinutes(31));

        assertThat(store.active(Duration.ofMinutes(30))).isEmpty();
        // But it is still present in the ageless view.
        assertThat(store.unacknowledged()).hasSize(1);
    }

    @Test
    @DisplayName("active(maxAge) excludes a warning once it has been acknowledged, even if young")
    void activeExcludesAcknowledged() {
        store.raise(Severity.WARNING, "QUEUE_HEALTH", "backlog growing");
        var id = store.snapshot().warnings().get(0).id();

        store.acknowledge(id);

        assertThat(store.active(Duration.ofMinutes(30))).isEmpty();
    }

    @Test
    @DisplayName("critical() returns only unacked CRITICAL warnings, regardless of age")
    void criticalIsSeverityAndAckFiltered() {
        store.raise(Severity.CRITICAL, "CONFIGURATION", "mediator 501");
        store.raise(Severity.WARNING, "ROUTING", "unknown pool");
        var criticalId = store.snapshot().warnings().stream()
                .filter(w -> w.severity() == Severity.CRITICAL)
                .findFirst().orElseThrow().id();

        // Ages far past the 30-minute "active" window used elsewhere:
        // critical() must still return it, because health's
        // criticalWarnings count is NOT age-bounded (§9.4).
        clock.advance(Duration.ofHours(2));
        assertThat(store.critical()).hasSize(1);
        assertThat(store.critical().get(0).category()).isEqualTo("CONFIGURATION");

        // Acknowledging the critical warning removes it from critical().
        store.acknowledge(criticalId);
        assertThat(store.critical()).isEmpty();
    }

    @Test
    @DisplayName("the store evicts the oldest 10% once it reaches MAX_WARNINGS, before inserting the new one")
    void evictsOldestTenPercentAtCapacity() {
        // Fill to exactly MAX_WARNINGS, each one minute apart so createdAt
        // order is unambiguous.
        for (int i = 0; i < WarningStore.MAX_WARNINGS; i++) {
            store.raise(Severity.INFO, "ROUTING", "warning-" + i);
            clock.advance(Duration.ofMinutes(1));
        }
        assertThat(store.count()).isEqualTo(WarningStore.MAX_WARNINGS);

        var oldestMessage = store.snapshot().warnings().stream()
                .min(java.util.Comparator.comparing(WarningStore.Notice::createdAt))
                .orElseThrow().message();
        assertThat(oldestMessage).isEqualTo("warning-0");

        // One more push: at capacity (1000), evictOldestLocked() must drop
        // the oldest 10% = 100 warnings BEFORE the new one is inserted, so
        // the resulting size is 1000 - 100 + 1 = 901, and warning-0..99 must
        // be gone while warning-100 survives.
        store.raise(Severity.INFO, "ROUTING", "overflow");

        assertThat(store.count()).isEqualTo(WarningStore.MAX_WARNINGS - 100 + 1);
        var messages = store.snapshot().warnings().stream().map(WarningStore.Notice::message).toList();
        assertThat(messages).doesNotContain("warning-0", "warning-50", "warning-99");
        assertThat(messages).contains("warning-100", "warning-999", "overflow");
    }

    @Test
    @DisplayName("cleanup() auto-acknowledges warnings older than AUTO_ACKNOWLEDGE_AGE")
    void cleanupAutoAcknowledgesOldWarnings() {
        store.raise(Severity.WARNING, "STALL", "aging warning");
        var id = store.snapshot().warnings().get(0).id();

        // Just under the auto-ack age: cleanup() must NOT touch it.
        clock.advance(WarningStore.AUTO_ACKNOWLEDGE_AGE.minusMinutes(1));
        store.cleanup();
        assertThat(store.snapshot().warnings().get(0).acknowledged())
                .as("a warning just under the auto-ack age must stay unacknowledged")
                .isFalse();

        // Push it past the threshold.
        clock.advance(Duration.ofMinutes(2));
        store.cleanup();
        var acked = store.snapshot().warnings().stream()
                .filter(w -> w.id().equals(id)).findFirst();
        // Because AUTO_ACKNOWLEDGE_AGE == MAX_WARNING_AGE (the documented
        // "moot" case, constant 45), this same cleanup() call also deletes
        // it for being past MAX_WARNING_AGE -- so it won't be found at all,
        // which itself pins the "auto-ack is moot" behaviour.
        assertThat(acked).isEmpty();
    }

    @Test
    @DisplayName("cleanup() drops warnings older than MAX_WARNING_AGE, exactly at the boundary is kept")
    void cleanupDropsOnlyStrictlyOlderThanMaxAge() {
        store.raise(Severity.INFO, "ROUTING", "boundary case");

        clock.advance(WarningStore.MAX_WARNING_AGE); // exactly 8h -- not yet over
        store.cleanup();
        assertThat(store.count())
                .as("a warning exactly MAX_WARNING_AGE old is not yet older than the limit")
                .isEqualTo(1);

        clock.advance(Duration.ofMinutes(1)); // now strictly over
        store.cleanup();
        assertThat(store.count())
                .as("a warning past MAX_WARNING_AGE must be dropped by cleanup()")
                .isZero();
    }

    @Test
    @DisplayName("cleanup() does not touch a warning already acknowledged before the max age, once past it it is still dropped")
    void cleanupDropsAcknowledgedTooOnceTooOld() {
        store.raise(Severity.INFO, "ROUTING", "acked early");
        var id = store.snapshot().warnings().get(0).id();
        store.acknowledge(id);

        clock.advance(WarningStore.MAX_WARNING_AGE.plusMinutes(1));
        store.cleanup();

        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("snapshot() reflects every raised warning with correct field mapping (category/severity/message)")
    void snapshotShape() {
        store.raise(Severity.CRITICAL, "CONFIGURATION", "mediator returned 501");

        var snap = store.snapshot();
        assertThat(snap.warnings()).hasSize(1);
        var w = snap.warnings().get(0);
        assertThat(w.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(w.category()).isEqualTo("CONFIGURATION");
        assertThat(w.message()).isEqualTo("mediator returned 501");
        assertThat(w.source()).isNotBlank();
    }

    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

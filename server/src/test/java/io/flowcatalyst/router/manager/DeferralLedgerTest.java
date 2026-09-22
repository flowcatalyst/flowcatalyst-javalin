package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// D6 (`docs/spec/router-hol-deferral.md` §1) — ported from Go's
/// `TestDeferralLedgerCountsWhatIsStillOut`.
class DeferralLedgerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("D6: outstanding prunes everything due at or before now, and earliest is the minimum "
            + "whatever the insertion order (mutant: never prune)")
    void outstandingPrunesAndEarliestIsTheMinimum() {
        var ledger = new DeferralLedger();
        assertThat(ledger.earliest()).isEmpty();

        ledger.add(NOW.plusSeconds(3));
        ledger.add(NOW.minusSeconds(1)); // already due
        ledger.add(NOW.plusSeconds(1));

        assertThat(ledger.earliest()).contains(NOW.minusSeconds(1));

        assertThat(ledger.outstanding(NOW)).as("the one already due is pruned").isEqualTo(2);
        assertThat(ledger.outstanding(NOW.plusSeconds(2))).isEqualTo(1);
        assertThat(ledger.outstanding(NOW.plusSeconds(60))).isZero();
        assertThat(ledger.earliest()).as("everything is pruned by now").isEmpty();
    }

    @Test
    @DisplayName("D6: a deferral for a queue identifier with no active consumer is dropped without error "
            + "(mutant: an unregistered identifier still gets booked)")
    void aDeferralForAnUnregisteredQueueIsDroppedWithoutError() throws InterruptedException {
        var tracker = new InFlightTracker(Clock.systemUTC());
        var manager = new RouterManager(tracker, Warnings.NO_OP, Clock.systemUTC(),
                cfg -> new Pool(cfg, BLOCKED, NO_OP_BROKER, PoolMetrics.NO_OP, Clock.systemUTC()));
        var pool = new Pool(new Pool.Config("D6-POOL", 1, 0), BLOCKED, NO_OP_BROKER, PoolMetrics.NO_OP,
                Clock.systemUTC());
        // registerPool wires Pool#onDeferral to RouterManager#noteDeferral —
        // the only path that can book a ledger entry.
        manager.registerPool("D6-POOL", pool);
        // Deliberately never manager.registerConsumer(...) for "ghost-queue":
        // there is no ledger for it, by construction.
        try {
            var capacity = pool.config().queueCapacity();
            pool.submit(message("occupy", "ghost-queue")); // takes the one worker, blocks forever
            assertThat(ENTERED.await(2, TimeUnit.SECONDS)).as("the worker must actually be occupied").isTrue();
            for (int i = 0; i < capacity; i++) {
                pool.submit(message("m" + i, "ghost-queue"));
            }
            // The buffer is now exactly at capacity; one more overflows.
            pool.submit(message("overflow", "ghost-queue"));

            assertThat(pool.totalDeferred()).as("the defer itself must still happen").isPositive();
            assertThat(manager.deferralLedger("ghost-queue").outstanding(Instant.now()))
                    .as("nothing was booked for an identifier this manager never registered a consumer for")
                    .isZero();
        } finally {
            pool.close();
            manager.close();
        }
    }

    private static final CountDownLatch ENTERED = new CountDownLatch(1);

    /// Blocks the single worker forever so the buffer actually fills instead
    /// of draining as fast as it is submitted to.
    private static final io.flowcatalyst.router.pool.Mediator BLOCKED = (msg, recordFailure) -> {
        ENTERED.countDown();
        Thread.sleep(Duration.ofMinutes(5));
        return MediationOutcome.Success.of(200);
    };

    private static QueuedMessage message(String id, String queueId) {
        return QueuedMessage.of(
                new Message(id, "D6-POOL", null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.IMMEDIATE),
                "b-" + id, "receipt-" + id, queueId);
    }

    private static final Broker NO_OP_BROKER = new Broker() {
        @Override
        public void ack(QueuedMessage message) {
        }

        @Override
        public void defer(QueuedMessage message, Duration delay) {
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public void release(QueuedMessage message) {
        }

        @Override
        public boolean honoursDelayedReturn(QueuedMessage message) {
            return true;
        }
    };
}

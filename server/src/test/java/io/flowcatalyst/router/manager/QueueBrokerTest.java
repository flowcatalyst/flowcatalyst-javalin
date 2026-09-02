package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Acknowledger;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [QueueBroker]'s layer-2 dedup backstop — `owns` (`docs/spec/router.md`
/// §2.1 `EnsureTracked`, `docs/spec/router-completion.md` unit 3).
class QueueBrokerTest {

    private final Clock clock = Clock.systemUTC();
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final FakeAcknowledger queue = new FakeAcknowledger();
    private final QueueBroker broker = new QueueBroker(Map.of("queue-1", queue), tracker, clock);

    @Test
    @DisplayName("owns re-establishes ownership when the route-time entry was reaped")
    void ownsRestoresAReapedEntry() {
        // Nothing was ever registered for this message — the shape a
        // route-time entry takes once the reaper has dropped it while the
        // message sat buffered (§7's stall/reap housekeeping).
        var message = message("m1", "b1");
        assertThat(tracker.size()).isZero();

        assertThat(broker.owns(message)).isTrue();

        // Not just answered true — an entry now exists, which is what
        // protects the NEXT copy from being wrongly treated as new too.
        assertThat(tracker.size()).as("ownership is re-established, not merely answered true").isOne();
    }

    @Test
    @DisplayName("owns answers false when a rival broker copy has since claimed the same application id")
    void ownsIsFalseForARivalBrokerCopy() {
        var now = Instant.now();
        // A different broker delivery of the same application id already
        // owns the pipeline — an external requeue, or another instance's
        // copy, registered after this one was reaped.
        tracker.register(new InFlightMessage("m1", "b-rival", "POOL-A", "queue-1",
                now, now, "", "batch-1", "receipt-rival", 0));

        var thisAttempt = message("m1", "b-this-copy");

        assertThat(broker.owns(thisAttempt)).isFalse();
        // The rival's entry is untouched — this attempt does not steal
        // ownership, it only checks for it.
        assertThat(tracker.freshestHandle("m1")).contains("receipt-rival");
    }

    @Test
    @DisplayName("owns answers true for the message that already owns the entry")
    void ownsIsTrueForTheCurrentOwner() {
        var now = Instant.now();
        tracker.register(new InFlightMessage("m1", "b1", "POOL-A", "queue-1",
                now, now, "", "batch-1", "receipt-m1", 0));

        assertThat(broker.owns(message("m1", "b1"))).isTrue();
    }

    // ── R-26/X-11: lingering consumers ─────────────────────────────────

    @Test
    @DisplayName("R-26/X-11: a queue dropped from config still resolves its old consumer for ack, and closes it only once the tracker clears")
    void droppedQueueLingersUntilTrackerClears() {
        var fakeConsumer = new FakeConsumer("queue-1");
        var manager = new RouterManager(tracker, io.flowcatalyst.router.observability.Warnings.NO_OP, clock,
                cfg -> new io.flowcatalyst.router.pool.Pool(cfg,
                        (msg, recordFailure) -> io.flowcatalyst.router.wire.MediationOutcome.Success.of(200),
                        NO_OP_POOL_BROKER, io.flowcatalyst.router.pool.PoolMetrics.NO_OP, clock));
        try {
            manager.reconfigure(
                    new io.flowcatalyst.router.config.RouterConfig(java.util.List.of(),
                            java.util.List.of(io.flowcatalyst.router.config.QueueConfig.of("queue-1"))),
                    queue -> java.util.Optional.of(fakeConsumer));

            var localBroker = new QueueBroker(queueId -> manager.consumer(queueId).orElse(null), tracker, clock);
            var message = message("m1", "b1");
            tracker.register(RouterManager.inFlight(message, "1", clock.instant()));

            // The queue disappears from config: the OLD consumer must not be
            // torn down while the tracker still references it.
            manager.reconfigure(io.flowcatalyst.router.config.RouterConfig.EMPTY, queue -> java.util.Optional.empty());

            assertThat(manager.consumer("queue-1")).as("still resolvable while lingering").isPresent();
            assertThat(fakeConsumer.closed).as("not closed while the tracker still references it").isFalse();

            localBroker.ack(message);

            assertThat(fakeConsumer.acked).as("the ack reaches the OLD consumer object").contains("m1");
            assertThat(fakeConsumer.closed).as("still not closed synchronously with the ack").isFalse();

            manager.retireLingeringConsumers();

            assertThat(fakeConsumer.closed).as("closed once housekeeping finds nothing left for its queue").isTrue();
            assertThat(manager.consumer("queue-1")).as("gone once retired").isEmpty();
        } finally {
            manager.pools().values().forEach(io.flowcatalyst.router.pool.Pool::close);
        }
    }

    private static final io.flowcatalyst.router.pool.Broker NO_OP_POOL_BROKER =
            new io.flowcatalyst.router.pool.Broker() {
                @Override
                public void ack(QueuedMessage message) {
                }

                @Override
                public void nack(QueuedMessage message, Duration delay) {
                }

                @Override
                public void release(QueuedMessage message) {
                }
            };

    private static QueuedMessage message(String id, String brokerId) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.IMMEDIATE),
                brokerId, "receipt-" + brokerId, "queue-1");
    }

    private static final class FakeConsumer implements io.flowcatalyst.router.queue.Consumer {
        private final String id;
        final java.util.List<String> acked = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean closed;

        FakeConsumer(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) {
            return PollResult.empty();
        }

        @Override
        public boolean ack(QueuedMessage message) {
            acked.add(message.id());
            return true;
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public java.util.Optional<io.flowcatalyst.router.queue.QueueMetrics> metrics() {
            return java.util.Optional.empty();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeAcknowledger implements Acknowledger {
        @Override
        public String identifier() {
            return "queue-1";
        }

        @Override
        public boolean ack(QueuedMessage message) {
            return true;
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }
    }
}

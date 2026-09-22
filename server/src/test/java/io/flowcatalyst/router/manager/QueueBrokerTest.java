package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Acknowledger;
import io.flowcatalyst.router.queue.ConsumerBuild;
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
import static org.assertj.core.api.Assertions.assertThatCode;

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

    // ── broker id collisions across queues ──────────────────────────────

    @Test
    @DisplayName("ack does not cross-wire when two queues' broker ids collide (docs/spec/router.md §2, key #2 scoped by queue)")
    void ackDoesNotCrossWireOnACollidingBrokerId() {
        // A NATS broker id is `<streamSeq>:<consumerSeq>` — unique only
        // within its own stream. Two different queues routinely deliver a
        // message with the very same broker id, e.g. "9:9" for each
        // stream's ninth message (`docs/spec/router.md` §7.4; observed in
        // `bench/router/results/java-nats-q8-c1-fixed.server.log`).
        var s1Queue = new FakeAcknowledger("S1/router");
        var s2Queue = new FakeAcknowledger("S2/router");
        var localTracker = new InFlightTracker(clock);
        var localBroker = new QueueBroker(Map.of("S1/router", s1Queue, "S2/router", s2Queue), localTracker, clock);

        // S2 registers FIRST, so it is the entry that an unscoped index
        // would keep in byMessageId — S1's later arrival is what the bug
        // treats as "the same delivery again" and uses to steal S2's slot.
        var now = Instant.now();
        localTracker.register(new InFlightMessage("m-s2", "9:9", "POOL-A", "S2/router",
                now, now, "", "batch-1", "S2:9", 0));
        localTracker.register(new InFlightMessage("m-s1", "9:9", "POOL-A", "S1/router",
                now, now, "", "batch-1", "S1:9", 0));

        var s2Message = QueuedMessage.of(
                new Message("m-s2", "", null, null, MediationType.HTTP, "https://x.test/h", null, false,
                        DispatchMode.IMMEDIATE),
                "9:9", "S2:9", "S2/router");

        localBroker.ack(s2Message);

        // Load-bearing: the S2 consumer must receive the ack with ITS OWN
        // receipt handle. An unscoped broker-id index makes register() treat
        // S1's later arrival as a redelivery of S2's message, swapping S2's
        // tracked entry to S1's handle and never tracking S1 at all — so
        // acking S2 lands its own receipt-substitution logic on S1's handle
        // instead: the exact defect that produced "nats: no pending message
        // for receipt BENCH1:9 on queue BENCH4/router".
        assertThat(s2Queue.ackedReceipts).containsExactly("S2:9");
        // S1's consumer must never see an ack at all — it wasn't touched.
        assertThat(s1Queue.ackedReceipts).isEmpty();
        // S1's entry must still be in flight, on its own receipt handle —
        // never having been swallowed as a phantom "redelivery" of S2's.
        assertThat(localTracker.freshestHandle("m-s1")).contains("S1:9");
    }

    // ── R5: honoursDelayedReturn (docs/spec/router-deferral-handback.md) ──

    @Test
    @DisplayName("T14: honoursDelayedReturn answers from the message's own consumer, and false for an unregistered queue")
    void honoursDelayedReturnAnswersFromTheConsumer() {
        var fakeConsumer = new FakeConsumer("queue-1");
        var localBroker = new QueueBroker(Map.of("queue-1", fakeConsumer), tracker, clock);
        var message = message("m1", "b1");

        // Not a hardcoded constant: the SAME queue id answers differently as
        // its own consumer's opinion changes.
        fakeConsumer.honoursDelayedReturn = true;
        assertThat(localBroker.honoursDelayedReturn(message)).isTrue();

        fakeConsumer.honoursDelayedReturn = false;
        assertThat(localBroker.honoursDelayedReturn(message)).isFalse();

        // A queue with no registered consumer at all — deregistered, or
        // never known — keeps the message in memory rather than nacking it
        // into a hand-back nothing is there to honour.
        var unregistered = QueuedMessage.of(
                new Message("m2", "", null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.IMMEDIATE),
                "b2", "receipt-b2", "no-such-queue");
        assertThat(localBroker.honoursDelayedReturn(unregistered)).isFalse();
    }

    // ── §2: defer reaches the consumer's own defer, not nack ────────────

    @Test
    @DisplayName("T12: no consumer registered for the queue: the entry is REMOVED, not marked deferred "
            + "— there is nothing to defer to")
    void deferWithNoRegisteredConsumerRemovesRatherThanMarks() {
        var localBroker = new QueueBroker(Map.<String, Acknowledger>of(), tracker, clock);
        var message = message("m1", "b1");
        tracker.register(RouterManager.inFlight(message, "1", clock.instant()));

        localBroker.defer(message, Duration.ofSeconds(45));

        assertThat(tracker.size()).isZero();
        assertThat(tracker.deferredSize()).as("nothing to come back — removed, exactly as before").isZero();
        assertThat(tracker.freshestHandle("m1")).as("truly gone, not just excluded from size()").isEmpty();
    }

    @Test
    @DisplayName("owner ruling 2026-09-22: defer reaches the message's own consumer's defer "
            + "(freshest handle substituted), and MARKS the entry deferred rather than releasing it")
    void deferReachesTheConsumersOwnDeferAndMarksTheEntryDeferred() {
        var message = message("m1", "b1");
        tracker.register(RouterManager.inFlight(message, "1", clock.instant()));

        broker.defer(message, Duration.ofSeconds(45));

        assertThat(queue.deferredReceipts).as("reached defer, not nack").containsEntry("receipt-b1",
                Duration.ofSeconds(45));
        // T12 (catch-up slice C4, docs/spec/router-hol-deferral.md §Addendum):
        // the entry is KEPT, marked deferred — size() (non-deferred count)
        // drops to zero, but the entry has not actually been released; a
        // second broker copy of "m1" must still be recognised as a duplicate
        // rather than delivered as new, which freshestHandle proves the
        // entry is still there to answer.
        assertThat(tracker.size()).as("a deferred entry does not count as owned work").isZero();
        assertThat(tracker.deferredSize()).as("but it is not gone").isOne();
        assertThat(tracker.freshestHandle("m1")).as("the entry itself is kept, not removed")
                .contains("receipt-b1");
    }

    @Test
    @DisplayName("T12: the mark stands even when the broker's own defer call fails "
            + "(mutant: mark only after a successful broker call)")
    void deferMarksTheEntryEvenWhenTheBrokersOwnCallFails() {
        Acknowledger throwing = new Acknowledger() {
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

            @Override
            public void defer(QueuedMessage message, Duration delay) {
                throw new RuntimeException("broker unreachable");
            }

            @Override
            public boolean honoursDelayedReturn() {
                return true;
            }
        };
        var localBroker = new QueueBroker(Map.of("queue-1", throwing), tracker, clock);
        var message = message("m1", "b1");
        tracker.register(RouterManager.inFlight(message, "1", clock.instant()));

        assertThatCode(() -> localBroker.defer(message, Duration.ofSeconds(45)))
                .as("Acknowledger#defer is documented best-effort/must-not-throw, but the mark must not "
                        + "depend on that promise holding")
                .isInstanceOf(RuntimeException.class);
        assertThat(tracker.deferredSize()).as("marked before the broker call, not after").isOne();
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
                    queue -> ConsumerBuild.of(fakeConsumer));

            var localBroker = new QueueBroker(queueId -> manager.consumer(queueId).orElse(null), tracker, clock);
            var message = message("m1", "b1");
            tracker.register(RouterManager.inFlight(message, "1", clock.instant()));

            // The queue disappears from config: the OLD consumer must not be
            // torn down while the tracker still references it.
            manager.reconfigure(io.flowcatalyst.router.config.RouterConfig.EMPTY, queue -> ConsumerBuild.FAILED);

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
                public void defer(QueuedMessage message, Duration delay) {
                    nack(message, delay);
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
        public void defer(QueuedMessage message, Duration delay) {
            nack(message, delay);
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        volatile boolean honoursDelayedReturn = true;

        @Override
        public boolean honoursDelayedReturn() {
            return honoursDelayedReturn;
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
        private final String id;
        final java.util.List<String> ackedReceipts = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.Map<String, Duration> deferredReceipts = new java.util.concurrent.ConcurrentHashMap<>();

        FakeAcknowledger() {
            this("queue-1");
        }

        FakeAcknowledger(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public boolean ack(QueuedMessage message) {
            ackedReceipts.add(message.receiptHandle());
            return true;
        }

        @Override
        public boolean honoursDelayedReturn() {
            return true;
        }

        @Override
        public void defer(QueuedMessage message, Duration delay) {
            deferredReceipts.put(message.receiptHandle(), delay);
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }
    }
}

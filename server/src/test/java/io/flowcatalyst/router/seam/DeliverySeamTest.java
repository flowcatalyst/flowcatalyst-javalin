package io.flowcatalyst.router.seam;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.manager.QueueBroker;
import io.flowcatalyst.router.policy.RetryPolicy;
import io.flowcatalyst.router.pool.Mediator;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Acknowledger;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/// **Pool → QueueBroker → InFlightTracker, with nothing faked between them.**
///
/// `PoolTest` fakes the broker and `RouterEventsTest` drives the broker
/// directly, so each proves its own half. Neither exercises the seam, and the
/// seam is where the interesting behaviour lives: ownership and the broker
/// are two separate pieces of state that must agree, and every way they can
/// disagree loses a message.
///
/// - Ownership released but not acknowledged → the broker redelivers work
///   that was already done.
/// - Acknowledged but ownership held → the redelivery is classified as a
///   duplicate of a live delivery and dropped, and nothing runs it.
/// - Acknowledged on a stale receipt handle → the broker rejects the
///   acknowledgement and redelivers for ever.
///
/// The last one in particular is invisible to both existing suites: only
/// `QueueBroker` substitutes the freshest handle, and only the tracker knows
/// what it is.
class DeliverySeamTest {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    /// Collapsed so the decisions are what is asserted rather than the waiting.
    private static final Pool.Backoffs FAST = new Pool.Backoffs(
            new RetryPolicy(List.of(Duration.ofMillis(1), Duration.ofMillis(1)),
                    Duration.ofMillis(1), Duration.ofMillis(2), 12),
            new RetryPolicy(List.of(), Duration.ofMillis(1), Duration.ofMillis(2), 12));

    private final InFlightTracker tracker = new InFlightTracker(Clock.systemUTC());
    private final FakeQueue queue = new FakeQueue();
    private final QueueBroker broker = new QueueBroker(Map.of("queue-1", queue), tracker);
    private final ScriptedMediator mediator = new ScriptedMediator();
    private Pool pool;

    @AfterEach
    void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    @DisplayName("a delivered message is both acknowledged and no longer owned")
    void deliveredMessageIsAckedAndReleased() {
        mediator.answer("m1", MediationOutcome.Success.of(200));

        deliver(pool(FAST), "m1");

        assertThat(queue.acked).containsExactly("receipt-m1");
        // Ownership too, or the next delivery of this id is dropped as a
        // duplicate of a delivery that finished long ago.
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("an acknowledgement uses the handle from the latest redelivery")
    void ackUsesTheFreshestHandle() {
        // A visibility timeout lapses mid-delivery and the broker re-sends the
        // message. The tracker swaps in the newer receipt handle and tells the
        // copy to drop itself. When the original delivery finishes, the OLD
        // handle is already expired: acknowledging on it is refused, and the
        // message redelivers for ever despite having been delivered.
        mediator.block();
        var p = pool(FAST);
        p.submit(register(message("m1", "receipt-m1")));
        await(() -> mediator.inFlight("m1"));

        var redelivery = inFlight("m1", "receipt-m1-fresh");
        assertThat(tracker.register(redelivery)).isInstanceOf(InFlightTracker.Registration.Redelivery.class);
        mediator.answer("m1", MediationOutcome.Success.of(200));
        mediator.unblock();

        await(() -> !queue.acked.isEmpty());
        assertThat(queue.acked).containsExactly("receipt-m1-fresh");
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("a poison message is dropped from the broker and from ownership")
    void undeliverableMessageIsAckedAndReleased() {
        // 404: the request is wrong, not the target. Retrying it unchanged
        // cannot succeed, so it leaves — and must leave both places.
        mediator.answer("m1", MediationOutcome.ErrorConfig.undeliverable(404, "not found"));

        deliver(pool(FAST), "m1");

        assertThat(queue.acked).containsExactly("receipt-m1");
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("an unreachable target hands an IMMEDIATE message back to the broker")
    void unavailableTargetReturnsAnImmediateMessage() {
        // This test previously asserted the OPPOSITE — that the message was
        // retried in place for ever and ownership held — and justified it as
        // the Q2 ruling. That was a misreading: Q2 says do not DISCARD a
        // message, and handing it back to the broker is not discarding. It is
        // the only action that restores the broker's authority over it.
        //
        // Holding it here instead means the broker's expiry, redelivery count
        // and dead-letter queue can never act on it, the lapsed-visibility
        // redelivery is dropped as a duplicate, and the message occupies a
        // worker and a tracker entry for as long as the outage lasts.
        mediator.answer("m1", new MediationOutcome.ErrorConnection(30, "refused"));

        var p = pool(FAST);
        p.submit(register(message("m1", "receipt-m1")));

        await(() -> !queue.nacked.isEmpty());
        assertThat(queue.nacked).containsKey("receipt-m1");
        assertThat(queue.acked).isEmpty();
        // ONE attempt, not ten. Without this the test cannot tell the
        // unreachable-target branch from the retry-budget cap, which also
        // ends in a nack — it would pass with the branch deleted, which is
        // exactly what a decorative test looks like.
        assertThat(mediator.attempts("m1"))
                .as("an unreachable target is not worth nine more tries in-process")
                .isEqualTo(1);
        // Ownership goes with it, or the redelivery this nack asks for is
        // dropped as a duplicate of a delivery nobody is running.
        await(() -> tracker.size() == 0);
    }

    @Test
    @DisplayName("an endlessly deferring target eventually hands the message back")
    void inPlaceRetriesAreBounded() {
        // A 429 or ack:false forever is a healthy target that never accepts
        // the message. Retrying in place is right — up to a point. Past the
        // budget the message goes back so something outside this process can
        // finally see it.
        mediator.answer("m1", new MediationOutcome.RateLimited(1));

        var p = pool(FAST);
        p.submit(register(message("m1", "receipt-m1")));

        await(() -> !queue.nacked.isEmpty());
        assertThat(mediator.attempts("m1")).isEqualTo(Pool.MAX_IN_PIPELINE_ATTEMPTS);
        assertThat(queue.acked).isEmpty();
        await(() -> tracker.size() == 0);
    }

    @Test
    @DisplayName("each in-place retry advances the tracker's attempt count")
    void retriesAdvanceTheAttemptCount() {
        // The count two guards read. While it stayed at zero the stall
        // detector reported every legitimately retrying message as stalled,
        // and with force-nack on would hand it back mid-retry — two live
        // deliveries of one message from a single broker.
        mediator.answer("m1", new MediationOutcome.RateLimited(1));

        var p = pool(FAST);
        p.submit(register(message("m1", "receipt-m1")));

        await(() -> mediator.attempts("m1") >= 3);
        assertThat(tracker.snapshot())
                .as("the tracker must see the retries, not just the pool")
                .singleElement()
                .satisfies(entry -> assertThat(entry.attempts()).isGreaterThan(0));
    }

    @Test
    @DisplayName("an unreachable target hands a whole ordered group back and releases it")
    void unavailableTargetReturnsTheGroup() {
        // Ordered delivery makes the opposite choice, and must: blocking the
        // group in memory for an outage of unknown length would hold every
        // message behind the head. The group goes back to the broker instead
        // — which means ownership of every one of them has to go too, or the
        // redelivery this nack asks for arrives and is dropped.
        mediator.answer("g0", new MediationOutcome.CircuitOpen(30));

        var p = pool(FAST);
        for (int i = 0; i < 3; i++) {
            p.submit(register(ordered("g" + i, "orders")));
        }

        await(() -> queue.nacked.size() == 3);
        assertThat(queue.nacked).containsOnlyKeys("receipt-g0", "receipt-g1", "receipt-g2");
        assertThat(queue.acked).isEmpty();
        await(() -> tracker.size() == 0);
    }

    @Test
    @DisplayName("shutdown mid-backoff says nothing to the broker but still gives up ownership")
    void shutdownMidBackoffReleasesThroughTheRealBroker() {
        // The path that used to leak, now through the real broker rather than
        // a fake. Nothing is said to the queue on purpose: the message was
        // never acknowledged, so the broker's own redelivery recovers it, and
        // a nack would race that redelivery with our own.
        var slow = new Pool.Backoffs(
                new RetryPolicy(List.of(Duration.ofSeconds(60)), Duration.ofSeconds(60), Duration.ofSeconds(60), 12),
                new RetryPolicy(List.of(), Duration.ofSeconds(60), Duration.ofSeconds(60), 12));
        // RETRY_IN_PLACE, not RETURN_TO_BROKER: this test needs the worker
        // parked inside the in-pipeline retry loop's Thread.sleep when
        // close() runs, which only a target the pool keeps retrying itself
        // reaches — a 5xx now nacks straight to the broker instead (R-57).
        mediator.answer("m1", new MediationOutcome.RateLimited(30));
        var p = pool(slow);
        p.submit(register(message("m1", "receipt-m1")));
        await(() -> mediator.attempts("m1") == 1);

        p.close();

        assertThat(queue.acked).isEmpty();
        assertThat(queue.nacked).isEmpty();
        assertThat(tracker.size()).isZero();
        // Which is the whole point: the redelivery is now accepted as work.
        assertThat(tracker.register(inFlight("m1", "receipt-m1-redelivered")))
                .isEqualTo(InFlightTracker.Registration.NEW);
    }

    @Test
    @DisplayName("a queue deregistered mid-delivery still releases ownership")
    void deregisteredQueueStillReleasesOwnership() {
        // A reconfigure dropped the queue while the message was in flight.
        // There is nothing left to acknowledge it on — but keeping ownership
        // would strand the id for the reaper's fifteen minutes, and the
        // replacement consumer's redelivery would be dropped in the meantime.
        var orphaned = new QueueBroker(Map.<String, Acknowledger>of(), tracker);
        mediator.answer("m1", MediationOutcome.Success.of(200));
        var p = new Pool(new Pool.Config("POOL-A", 4, 0), FAST, mediator, orphaned,
                PoolMetrics.NO_OP, Clock.systemUTC());
        pool = p;

        p.submit(register(message("m1", "receipt-m1")));

        await(() -> tracker.size() == 0);
        assertThat(queue.acked).isEmpty();
    }

    // ── scaffolding ─────────────────────────────────────────────────────

    private Pool pool(Pool.Backoffs backoffs) {
        pool = new Pool(new Pool.Config("POOL-A", 4, 0), backoffs, mediator, broker,
                PoolMetrics.NO_OP, Clock.systemUTC());
        return pool;
    }

    private void deliver(Pool p, String id) {
        p.submit(register(message(id, "receipt-" + id)));
        await(() -> tracker.size() == 0);
    }

    /// Registers ownership the way the consumer loop does before dispatching.
    private QueuedMessage register(QueuedMessage message) {
        assertThat(tracker.register(inFlight(message.id(), message.receiptHandle())))
                .isEqualTo(InFlightTracker.Registration.NEW);
        return message;
    }

    private static InFlightMessage inFlight(String id, String receiptHandle) {
        return new InFlightMessage(id, "broker-" + id, "POOL-A", "queue-1",
                Instant.now(), Instant.now(), null, null, receiptHandle, 0);
    }

    private static QueuedMessage ordered(String id, String group) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h",
                        group, false, DispatchMode.BLOCK_ON_ERROR),
                "broker-" + id, "receipt-" + id, "queue-1");
    }

    private static QueuedMessage message(String id, String receiptHandle) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.NEXT_ON_ERROR),
                "broker-" + id, receiptHandle, "queue-1");
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition not met within " + AWAIT);
    }

    /// Records the receipt handle each call was made on — the field that
    /// separates a stale acknowledgement from a fresh one.
    private static final class FakeQueue implements Acknowledger {
        final List<String> acked = new CopyOnWriteArrayList<>();
        final Map<String, Duration> nacked = new ConcurrentHashMap<>();

        @Override
        public String identifier() {
            return "queue-1";
        }

        @Override
        public boolean ack(QueuedMessage message) {
            acked.add(message.receiptHandle());
            return true;
        }

        @Override
        public boolean honoursDelayedReturn() {
            return true;
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
            nacked.put(message.receiptHandle(), delay);
        }
    }

    private static final class ScriptedMediator implements Mediator {
        private final Map<String, MediationOutcome> answers = new ConcurrentHashMap<>();
        private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
        private final java.util.Set<String> running = ConcurrentHashMap.newKeySet();
        private volatile boolean blocked;

        void answer(String id, MediationOutcome outcome) {
            answers.put(id, outcome);
        }

        void block() {
            blocked = true;
        }

        void unblock() {
            blocked = false;
        }

        int attempts(String id) {
            return attempts.getOrDefault(id, 0);
        }

        boolean inFlight(String id) {
            return running.contains(id);
        }

        @Override
        public MediationOutcome deliver(Message message, boolean endsBurst) throws InterruptedException {
            attempts.merge(message.id(), 1, Integer::sum);
            running.add(message.id());
            try {
                while (blocked) {
                    Thread.sleep(2);
                }
            } finally {
                running.remove(message.id());
            }
            return answers.getOrDefault(message.id(), MediationOutcome.Success.of(200));
        }
    }
}

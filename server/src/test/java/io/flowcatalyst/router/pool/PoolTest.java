package io.flowcatalyst.router.pool;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.jfr.DispatchEvent;
import io.flowcatalyst.router.observability.jfr.GroupDecisionEvent;
import io.flowcatalyst.router.observability.jfr.Recorded;
import io.flowcatalyst.router.policy.RetryPolicy;
import io.flowcatalyst.router.wire.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// The pool's dispatch decisions (`docs/spec/router.md` §3.4, §3.5).
///
/// Backoff curves are collapsed to near-zero so the *decisions* are what is
/// asserted rather than the waiting. The curves themselves are pinned by
/// `RetryPolicyTest`; duplicating them here would only make the suite slow.
class PoolTest {

    private static final Pool.Backoffs FAST = new Pool.Backoffs(
            new RetryPolicy(List.of(Duration.ofMillis(1), Duration.ofMillis(1)),
                    Duration.ofMillis(1), Duration.ofMillis(2), 12),
            new RetryPolicy(List.of(), Duration.ofMillis(1), Duration.ofMillis(2), 12));

    private final ScriptedMediator mediator = new ScriptedMediator();
    private final RecordingBroker broker = new RecordingBroker();
    private final CountingMetrics metrics = new CountingMetrics();
    private Pool pool;

    @AfterEach
    void closePool() {
        if (pool != null) {
            pool.close();
        }
    }

    private Pool pool(int concurrency, int rpm) {
        pool = new Pool(new Pool.Config("POOL-A", concurrency, rpm), FAST, mediator, broker, metrics, Clock.systemUTC());
        return pool;
    }

    // ── IMMEDIATE ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a delivered message is acknowledged once")
    void successAcks() {
        mediator.answer("m1", MediationOutcome.Success.of(200));

        pool(4, 0).submit(immediate("m1"));

        await(() -> broker.acked.contains("m1"));
        assertThat(broker.nacked).isEmpty();
        assertThat(metrics.successes.get()).isOne();
    }

    @Test
    @DisplayName("a 4xx is dropped rather than retried forever")
    void configErrorIsDropped() {
        // The request was wrong, not the target: retrying it unchanged cannot
        // succeed, so keeping it would be an infinite loop over a bad message.
        mediator.answer("m1", new MediationOutcome.ErrorConfig(400, "bad"));

        pool(4, 0).submit(immediate("m1"));

        await(() -> broker.acked.contains("m1"));
        assertThat(metrics.failures.get()).isOne();
    }

    @Test
    @DisplayName("an IMMEDIATE retry stays in the pipeline and never touches the broker")
    void immediateRetryNeverNacks() {
        // §3.6's invariant, and the one Go's guardrail test pins: a retryable
        // outcome keeps the message here, holding its place and its attempt
        // count, rather than racing our retry against a redelivery.
        mediator.script("m1",
                new MediationOutcome.ErrorProcess(500, 30, "boom"),
                new MediationOutcome.ErrorProcess(500, 30, "boom"),
                MediationOutcome.Success.of(200));

        pool(4, 0).submit(immediate("m1"));

        await(() -> broker.acked.contains("m1"));
        assertThat(broker.nacked).isEmpty();
        assertThat(mediator.attempts("m1")).isEqualTo(3);
        assertThat(metrics.transients.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unexpected exception is a retry, not a lost message")
    void unexpectedExceptionRetries() {
        // The policy Go's panic recovery guarded, kept without the
        // scaffolding: per-thread isolation means the throw cannot take the
        // process down, but the message must still survive it.
        mediator.throwOnce("m1", new IllegalStateException("kaboom"));
        mediator.answer("m1", MediationOutcome.Success.of(200));

        pool(4, 0).submit(immediate("m1"));

        await(() -> broker.acked.contains("m1"));
        assertThat(mediator.attempts("m1")).isEqualTo(2);
    }

    // ── Backpressure and lifecycle ──────────────────────────────────────

    @Test
    @DisplayName("a stopped pool hands messages back rather than dropping them")
    void stoppedPoolNacks() {
        var p = pool(4, 0);
        p.stop();

        p.submit(immediate("m1"));

        assertThat(broker.nacked).containsKey("m1");
        assertThat(broker.acked).isEmpty();
    }

    @Test
    @DisplayName("a broker that never answers cannot hold the shutdown open")
    void handBackIsBounded() {
        // The messages are not lost by giving up: they were never
        // acknowledged, so the broker redelivers them on its own timer. What
        // would be lost is the shutdown itself — stop() is on the leadership
        // transition path, so one unresponsive broker would otherwise stall
        // the failover of every other queue behind it.
        mediator.block();
        broker.hangOnNack = true;
        var p = pool(1, 0);
        IntStream.range(0, 5).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));
        await(() -> p.queueSize() >= 4);

        var startedAt = System.nanoTime();
        p.stop();
        var took = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(took)
                .as("stop() must give up on the broker, not wait on it")
                .isLessThan(Pool.HANDBACK_TIMEOUT.plusSeconds(3));
        assertThat(broker.acked).isEmpty();
        broker.hangOnNack = false;
        mediator.unblock();
    }

    // ── Flight recorder ─────────────────────────────────────────────────

    @Test
    @DisplayName("a delivery attempt is recorded with its outcome and its target")
    void dispatchIsRecorded() throws Exception {
        mediator.answer("m1", MediationOutcome.Success.of(200));
        var p = pool(4, 0);

        var events = Recorded.from(DispatchEvent.class, () -> {
            p.submit(immediate("m1"));
            await(() -> broker.acked.contains("m1"));
        });

        assertThat(events).hasSize(1);
        var dispatch = events.getFirst();
        assertThat(dispatch.getString("pool")).isEqualTo("POOL-A");
        assertThat(dispatch.getString("messageId")).isEqualTo("m1");
        assertThat(dispatch.getString("outcome")).isEqualTo("Success");
        assertThat(dispatch.getString("disposition")).isEqualTo("DELIVERED");
        assertThat(dispatch.getInt("statusCode")).isEqualTo(200);
        // A duration event, so a recording carries the latency per attempt
        // without a histogram having been configured beforehand.
        assertThat(dispatch.getDuration()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a group decision records its blast radius, not just its verdict")
    void groupDecisionRecordsBlastRadius() throws Exception {
        // The event that would have made the ACK-deletion bug obvious: the
        // decision and the number of messages it takes with it, side by side.
        mediator.answer("m0", new MediationOutcome.CircuitOpen(30));
        var p = pool(1, 0);

        var events = Recorded.from(GroupDecisionEvent.class, () -> {
            IntStream.range(0, 3).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));
            await(() -> broker.nacked.size() == 3);
        });

        assertThat(events).hasSize(1);
        var decision = events.getFirst();
        assertThat(decision.getString("group")).isEqualTo("g");
        assertThat(decision.getString("dispatchMode")).isEqualTo("BLOCK_ON_ERROR");
        assertThat(decision.getString("disposition")).isEqualTo("RETURN_TO_BROKER");
        assertThat(decision.getString("decision")).isEqualTo("ReturnGroup");
        assertThat(decision.getInt("siblingsAffected")).isEqualTo(2);
        // No call was made, so there is no status to report.
        assertThat(decision.getInt("statusCode")).isZero();
    }

    @Test
    @DisplayName("shutdown during a backoff frees the message for its redelivery")
    void shutdownMidBackoffReleasesOwnership() {
        // The failure this pins is entirely invisible from the broker: no ack,
        // no nack, nothing recorded. The message is left OWNED by a process
        // that has exited, so when the broker redelivers it the next reader
        // classifies it as a duplicate of a live delivery and drops it. The
        // message then sits untouched until the reaper runs — fifteen minutes
        // later — and it happens on every rolling deploy.
        var slow = new Pool.Backoffs(
                new RetryPolicy(List.of(Duration.ofSeconds(60)), Duration.ofSeconds(60), Duration.ofSeconds(60), 12),
                new RetryPolicy(List.of(), Duration.ofSeconds(60), Duration.ofSeconds(60), 12));
        pool = new Pool(new Pool.Config("POOL-A", 4, 0), slow, mediator, broker, metrics, Clock.systemUTC());
        mediator.answer("m1", new MediationOutcome.ErrorProcess(500, 30, "boom"));

        var queued = immediate("m1");
        assertThat(broker.tracker.register(inFlight(queued))).isEqualTo(InFlightTracker.Registration.NEW);
        pool.submit(queued);
        // Failed once and now parked in the 60-second backoff.
        await(() -> mediator.attempts("m1") == 1);
        assertThat(broker.tracker.size()).isOne();

        pool.close();

        // Nothing was said to the broker — correct, the message was never
        // acknowledged, so the broker's own redelivery is what brings it back.
        assertThat(broker.acked).isEmpty();
        assertThat(broker.nacked).isEmpty();
        // But ownership is gone, so that redelivery is accepted as new work
        // rather than dropped as a duplicate of a delivery no one is running.
        assertThat(broker.tracker.size()).isZero();
        var redelivery = new InFlightMessage("m1", "broker-m1-again", "POOL-A", "queue-1",
                Instant.now(), Instant.now(), null, null, "receipt-m1-again", 0);
        assertThat(broker.tracker.register(redelivery)).isEqualTo(InFlightTracker.Registration.NEW);
    }

    private static InFlightMessage inFlight(QueuedMessage message) {
        return new InFlightMessage(message.id(), message.brokerMessageId(), "POOL-A",
                message.queueId(), Instant.now(), Instant.now(),
                null, null, message.receiptHandle(), 0);
    }

    @Test
    @DisplayName("a full pool pushes back instead of growing without bound")
    void capacityPushesBack() {
        // Capacity is max(concurrency*20, 50); block every delivery so
        // nothing drains and the queue fills.
        mediator.block();
        var p = pool(1, 0);
        int capacity = p.config().queueCapacity();

        IntStream.range(0, capacity + 20).forEach(i -> p.submit(immediate("m" + i)));

        // Excess is pushed back, not queued. The exact count varies by a
        // couple: a message that has already claimed a slot no longer counts
        // against the queue, which is correct — it is being delivered, not
        // waiting.
        await(() -> broker.nacked.size() >= 15);
        assertThat(p.queueSize()).isLessThanOrEqualTo(capacity);
        assertThat(broker.nacked.values()).allMatch(Pool.REJECTED_NACK_DELAY::equals);
        assertThat(broker.acked).isEmpty();
        mediator.unblock();
    }

    @Test
    @DisplayName("stopping hands back everything still queued")
    void stopNacksBufferedMessages() {
        mediator.block();
        var p = pool(1, 0);
        // One ordered group: the head occupies the drainer, the rest buffer.
        IntStream.range(0, 5).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));
        await(() -> p.queueSize() >= 4);

        p.stop();

        // Accepted but never delivered, so the broker is where they belong.
        assertThat(broker.nacked).hasSizeGreaterThanOrEqualTo(4);
        assertThat(broker.acked).isEmpty();
        mediator.unblock();
    }

    @Test
    @DisplayName("concurrency cannot be set to zero")
    void concurrencyMustStayPositive() {
        // A zero-capacity pool would accept messages and never deliver them.
        var p = pool(4, 0);

        assertThat(p.updateConcurrency(0)).isFalse();
        assertThat(p.updateConcurrency(-1)).isFalse();
        assertThat(p.updateConcurrency(8)).isTrue();
    }

    @Test
    @DisplayName("concurrency bounds simultaneous deliveries")
    void concurrencyIsBounded() {
        mediator.block();
        var p = pool(3, 0);

        IntStream.range(0, 10).forEach(i -> p.submit(immediate("m" + i)));

        await(() -> mediator.inFlight.get() == 3);
        // Held at the limit rather than creeping past it.
        sleepBriefly();
        assertThat(mediator.inFlight.get()).isEqualTo(3);
        mediator.unblock();
    }

    // ── Ordering ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a group is delivered strictly in order")
    void groupIsOrdered() {
        IntStream.range(0, 6).forEach(i -> mediator.answer("m" + i, MediationOutcome.Success.of(200)));
        var p = pool(8, 0);

        IntStream.range(0, 6).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));

        await(() -> broker.acked.size() == 6);
        assertThat(mediator.delivered).containsExactly("m0", "m1", "m2", "m3", "m4", "m5");
    }

    @Test
    @DisplayName("groups are delivered independently of one another")
    void groupsAreIndependent() {
        mediator.answer("a1", MediationOutcome.Success.of(200));
        mediator.answer("b1", MediationOutcome.Success.of(200));
        var p = pool(8, 0);

        p.submit(ordered("ga", "a1", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("gb", "b1", DispatchMode.BLOCK_ON_ERROR));

        await(() -> broker.acked.size() == 2);
    }

    @Test
    @DisplayName("an unavailable target sends the whole group back to the broker")
    void unavailableReturnsGroup() {
        // 503: a service cycling or misconfigured. Nothing is wrong with
        // these messages, so the broker holds them until it or the target
        // gives way — no budget burnt, nothing held in memory.
        mediator.always("m0", new MediationOutcome.ErrorProcess(503, 30, "unavailable"));
        var p = pool(2, 0);

        IntStream.range(0, 3).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));

        await(() -> broker.nacked.size() == 3);
        assertThat(broker.acked).isEmpty();
        // Straight back, without spending the rejection budget first.
        assertThat(mediator.attempts("m0")).isOne();
    }

    @Test
    @DisplayName("BLOCK_ON_ERROR: a rejected head is retried, then it and its siblings are ACKed")
    void blockOnErrorAcksGroupAfterBudget() {
        mediator.always("m0", new MediationOutcome.ErrorProcess(500, 30, "boom"));
        var p = pool(2, 0);

        IntStream.range(0, 3).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));

        await(() -> broker.acked.size() == 3);
        assertThat(broker.nacked).isEmpty();
        // Three attempts absorb a transient application fault before giving up.
        assertThat(mediator.attempts("m0")).isEqualTo(RetryPolicy.DELIVERY.burstSize());
        // Siblings were never delivered: the platform re-sends the group.
        assertThat(mediator.delivered).containsOnly("m0");
    }

    @Test
    @DisplayName("NEXT_ON_ERROR: the group continues past a rejected head")
    void nextOnErrorContinues() {
        // The Q1 deviation: Go blocks the group for both ordered modes.
        mediator.always("m0", new MediationOutcome.ErrorProcess(500, 30, "boom"));
        mediator.answer("m1", MediationOutcome.Success.of(200));
        mediator.answer("m2", MediationOutcome.Success.of(200));
        var p = pool(2, 0);

        IntStream.range(0, 3).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.NEXT_ON_ERROR)));

        await(() -> broker.acked.size() == 3);
        assertThat(mediator.delivered).containsExactly("m0", "m0", "m0", "m1", "m2");
    }

    // ── Flush, rate limit ───────────────────────────────────────────────

    @Test
    @DisplayName("flushGroup suppresses the rest of the group without delivering it")
    void flushGroupSuppressesSiblings() {
        mediator.answer("m0", MediationOutcome.Success.flushing(200, 60));
        var p = pool(2, 0);

        IntStream.range(0, 4).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));

        await(() -> broker.acked.size() == 4);
        // Only the head was delivered; the rest were ACKed unseen.
        assertThat(mediator.delivered).containsExactly("m0");
        assertThat(metrics.suppressed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("an ungrouped flushGroup is ignored rather than suppressing the empty bucket")
    void ungroupedFlushIsIgnored() {
        mediator.answer("m1", MediationOutcome.Success.flushing(200, 60));
        mediator.answer("m2", MediationOutcome.Success.of(200));
        var p = pool(4, 0);

        p.submit(immediate("m1"));
        await(() -> broker.acked.contains("m1"));
        p.submit(immediate("m2"));

        await(() -> broker.acked.contains("m2"));
        assertThat(metrics.suppressed.get()).isZero();
    }

    @Test
    @DisplayName("the pool's own rate limiting is counted apart from the target's")
    void rateLimitingIsCounted() {
        // Conflating them hides which side is the bottleneck (§13 Q9).
        IntStream.range(0, 4).forEach(i -> mediator.answer("m" + i, MediationOutcome.Success.of(200)));
        // Concurrency 1 so the messages are strictly serialised. With more,
        // all three could check the limiter before any of them consumed a
        // token, and none would observe an empty bucket — which made an
        // earlier version of this test fail about two runs in five.
        var p = pool(1, 1);

        IntStream.range(0, 3).forEach(i -> p.submit(immediate("m" + i)));

        // The first takes the only token, so the second must find it gone.
        // Only one observation is assertable: having recorded it, the second
        // message then waits ~60s for the next token, holding the single
        // worker, so a third never runs. Expecting two would deadlock the
        // assertion rather than test anything.
        await(() -> metrics.rateLimited.get() >= 1);
    }

    @Test
    @DisplayName("active workers counts deliveries in progress, not messages waiting")
    void activeWorkersCountsInProgressDeliveries() {
        // queueSize counts what is WAITING; activeWorkers counts what is
        // happening. Together they say whether a pool is busy or backed up —
        // either number alone cannot.
        mediator.block();
        var p = pool(3, 0);

        IntStream.range(0, 10).forEach(i -> p.submit(immediate("m" + i)));

        await(() -> p.activeWorkers() == 3);
        assertThat(p.queueSize()).as("the rest are waiting, not working").isGreaterThan(0);

        mediator.unblock();
        await(() -> p.activeWorkers() == 0);
    }

    @Test
    @DisplayName("a worker releases its count even when the delivery throws")
    void activeWorkersReleasedOnFailure() {
        // Leaking the count on the exceptional path would make a pool look
        // permanently busier than it is, and the gauge is what an operator
        // uses to decide whether to scale it.
        mediator.throwOnce("m1", new IllegalStateException("kaboom"));
        mediator.answer("m1", MediationOutcome.Success.of(200));
        var p = pool(2, 0);

        p.submit(immediate("m1"));

        await(() -> broker.acked.contains("m1"));
        assertThat(p.activeWorkers()).isZero();
    }

    @Test
    @DisplayName("the group count tracks ordered groups holding work")
    void messageGroupCountTracksOrderedGroups() {
        // An ordered group is a serialisation point, so a rising count is the
        // shape of ordered backlog that queueSize alone would not distinguish
        // from a busy IMMEDIATE pool.
        mediator.block();
        var p = pool(4, 0);

        p.submit(ordered("alpha", "a1", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("beta", "b1", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("beta", "b2", DispatchMode.BLOCK_ON_ERROR));

        await(() -> p.messageGroupCount() == 2);
        assertThat(p.messageGroupCount()).as("two groups, three messages").isEqualTo(2);
        mediator.unblock();
    }

    // ── Fakes ───────────────────────────────────────────────────────────

    private static final int AWAIT_MILLIS = 5_000;

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofMillis(AWAIT_MILLIS).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("condition not met within " + AWAIT_MILLIS + "ms");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(Duration.ofMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static QueuedMessage immediate(String id) {
        return message(id, null, DispatchMode.IMMEDIATE);
    }

    private static QueuedMessage ordered(String group, String id, DispatchMode mode) {
        return message(id, group, mode);
    }

    private static QueuedMessage message(String id, String group, DispatchMode mode) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h", group, false, mode),
                "broker-" + id, "receipt-" + id, "queue-1");
    }

    /// A mediator whose answers are scripted per message id.
    private static final class ScriptedMediator implements Mediator {
        private final Map<String, Deque<MediationOutcome>> scripts = new ConcurrentHashMap<>();
        private final Map<String, MediationOutcome> standing = new ConcurrentHashMap<>();
        private final Map<String, RuntimeException> throwOnce = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        private volatile boolean blocked;

        void answer(String id, MediationOutcome outcome) {
            standing.put(id, outcome);
        }

        void always(String id, MediationOutcome outcome) {
            standing.put(id, outcome);
        }

        void script(String id, MediationOutcome... outcomes) {
            scripts.put(id, new ArrayDeque<>(List.of(outcomes)));
        }

        void throwOnce(String id, RuntimeException e) {
            throwOnce.put(id, e);
        }

        void block() {
            blocked = true;
        }

        void unblock() {
            blocked = false;
        }

        int attempts(String id) {
            var counter = attempts.get(id);
            return counter == null ? 0 : counter.get();
        }

        @Override
        public MediationOutcome deliver(Message message, boolean recordFailure) throws InterruptedException {
            var id = message.id();
            attempts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            delivered.add(id);
            inFlight.incrementAndGet();
            try {
                while (blocked) {
                    Thread.sleep(Duration.ofMillis(5));
                }
                var thrown = throwOnce.remove(id);
                if (thrown != null) {
                    throw thrown;
                }
                var script = scripts.get(id);
                if (script != null) {
                    synchronized (script) {
                        if (!script.isEmpty()) {
                            return script.pollFirst();
                        }
                    }
                }
                return standing.getOrDefault(id, MediationOutcome.Success.of(200));
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    /// Carries a **real** [InFlightTracker], because every one of these three
    /// methods exists to give ownership back. Recording the call proves only
    /// that a method ran; running the tracker proves the message is actually
    /// free for its next delivery.
    private static final class RecordingBroker implements Broker {
        /// Accepts the call and then never answers — the failure mode a
        /// timeout exists for, and the one a refused connection does not
        /// reproduce.
        volatile boolean hangOnNack;
        final List<String> acked = new CopyOnWriteArrayList<>();
        final Map<String, Duration> nacked = new ConcurrentHashMap<>();
        final InFlightTracker tracker = new InFlightTracker(Clock.systemUTC());

        @Override
        public void ack(QueuedMessage message) {
            acked.add(message.id());
            tracker.remove(message.id());
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
            if (hangOnNack) {
                try {
                    Thread.sleep(Duration.ofMinutes(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            nacked.put(message.id(), delay);
            tracker.remove(message.id());
        }

        @Override
        public void release(QueuedMessage message) {
            tracker.remove(message.id());
        }
    }

    private static final class CountingMetrics implements PoolMetrics {
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger transients = new AtomicInteger();
        final AtomicInteger rateLimited = new AtomicInteger();
        final AtomicInteger suppressed = new AtomicInteger();

        @Override
        public void recordSuccess(Duration took) {
            successes.incrementAndGet();
        }

        @Override
        public void recordFailure(Duration took) {
            failures.incrementAndGet();
        }

        @Override
        public void recordTransient(Duration took) {
            transients.incrementAndGet();
        }

        @Override
        public void recordRateLimited() {
            rateLimited.incrementAndGet();
        }

        @Override
        public void recordSuppressed() {
            suppressed.incrementAndGet();
        }
    }
}

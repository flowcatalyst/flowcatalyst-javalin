package io.flowcatalyst.router.pool;

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
        var p = pool(4, 2); // two per minute: the third is held

        IntStream.range(0, 3).forEach(i -> p.submit(immediate("m" + i)));

        await(() -> metrics.rateLimited.get() >= 1);
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

    private static final class RecordingBroker implements Broker {
        final List<String> acked = new CopyOnWriteArrayList<>();
        final Map<String, Duration> nacked = new ConcurrentHashMap<>();

        @Override
        public void ack(QueuedMessage message) {
            acked.add(message.id());
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
            nacked.put(message.id(), delay);
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

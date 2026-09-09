package io.flowcatalyst.router.pool;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.observability.jfr.DispatchEvent;
import io.flowcatalyst.router.observability.jfr.GroupDecisionEvent;
import io.flowcatalyst.testjfr.Recorded;
import io.flowcatalyst.router.policy.RetryPolicy;
import io.flowcatalyst.router.settled.BlockedSiblings;
import io.flowcatalyst.router.settled.SettledJob;
import io.flowcatalyst.router.settled.SettledReport;
import io.flowcatalyst.router.settled.SettledReporter;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
        mediator.answer("m1", MediationOutcome.ErrorConfig.undeliverable(400, "bad"));

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
        // Deferred, not ErrorProcess: ErrorProcess is now always
        // RETURN_TO_BROKER (R-57 moved the 5xx-that-retries-in-place
        // boundary to ErrorConfig.rejected, which is terminal on one
        // attempt instead). Deferred is still a RETRY_IN_PLACE outcome, so
        // it still pins this invariant.
        mediator.script("m1",
                new MediationOutcome.Deferred(200, 30, "not ready"),
                new MediationOutcome.Deferred(200, 30, "not ready"),
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
        // RETRY_IN_PLACE, not RETURN_TO_BROKER: the message must be parked
        // inside the sleep when close() runs. ErrorProcess is now always
        // RETURN_TO_BROKER (R-57), which would nack immediately instead.
        mediator.answer("m1", new MediationOutcome.RateLimited(30));

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

    @Test
    @DisplayName("a resize changes the limit that is actually enforced")
    void resizeRaisesTheEnforcedLimit() {
        // `updateConcurrency` returning true says nothing about whether the
        // pool then runs that many — which is all this was asserting before.
        // The semaphore is swapped wholesale rather than resized, so what
        // needs pinning is that new acquirers meet the NEW ceiling while the
        // workers holding permits from the old one are unaffected.
        mediator.block();
        var p = pool(2, 0);
        IntStream.range(0, 12).forEach(i -> p.submit(immediate("m" + i)));
        await(() -> mediator.inFlight.get() == 2);

        assertThat(p.updateConcurrency(5)).isTrue();

        // These twelve were submitted BEFORE the resize, so their workers are
        // already parked waiting for a permit. That is the whole point: a
        // resize the backlog cannot see is a resize that does not help the
        // situation an operator raises concurrency to fix.
        await(() -> mediator.inFlight.get() == 5);
        sleepBriefly();
        assertThat(mediator.inFlight.get())
                .as("the new limit governs the messages already waiting, not just future ones")
                .isEqualTo(5);
        mediator.unblock();
    }

    @Test
    @DisplayName("concurrency settles to the new limit once the old permits are gone")
    void resizeSettlesToTheNewLimit() {
        // Shrinking cannot evict work already running, so "settles to n" is a
        // claim about what happens AFTER those finish — the half that a test
        // taken at the moment of the resize would miss entirely.
        mediator.block();
        var p = pool(6, 0);
        IntStream.range(0, 6).forEach(i -> p.submit(immediate("old" + i)));
        await(() -> mediator.inFlight.get() == 6);

        assertThat(p.updateConcurrency(2)).isTrue();
        mediator.unblock();
        // Every permit on the old semaphore is now released.
        await(() -> broker.acked.size() == 6);

        mediator.block();
        IntStream.range(0, 10).forEach(i -> p.submit(immediate("new" + i)));

        await(() -> mediator.inFlight.get() == 2);
        sleepBriefly();
        assertThat(mediator.inFlight.get())
                .as("with the old permits gone, the new limit is the only one left")
                .isEqualTo(2);
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
    @DisplayName("A-01 gate OFF (default): BLOCK_ON_ERROR's untried siblings are released to the broker, not ACKed")
    void blockOnErrorReleasesSiblingsWhenGateIsOff() {
        // R-57: REJECTED is terminal on the first attempt, no bounded retry.
        // router-specification.md §0's MUST: with no platform to recover an
        // ACKed sibling, the router MUST keep releasing (NACKing) them —
        // ACKing all three here is exactly the violation this gate closes.
        mediator.always("m0", MediationOutcome.ErrorConfig.rejected(500, "boom"));
        var p = pool(2, 0);

        IntStream.range(0, 3).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));

        await(() -> broker.acked.size() == 1 && broker.nacked.size() == 2);
        // A counter that must change: if the old retry-then-give-up budget
        // were still running, this would be RetryPolicy.DELIVERY.burstSize().
        assertThat(mediator.attempts("m0")).isOne();
        assertThat(metrics.failures.get()).isOne();
        // Only the head is gone permanently; its siblings are still on the
        // broker, redeliverable, with a reason distinct from the head's.
        assertThat(broker.ackReasons.get("m0")).isEqualTo("rejected-group-blocked");
        assertThat(broker.nackReasons).containsEntry("m1", "rejected-group-released")
                .containsEntry("m2", "rejected-group-released");
        assertThat(broker.nacked.values()).allMatch(Pool.REJECTED_NACK_DELAY::equals);
        // Never delivered: the retry budget on them was never touched.
        assertThat(mediator.attempts("m1")).isZero();
        assertThat(mediator.attempts("m2")).isZero();
        assertThat(mediator.delivered).containsOnly("m0");
    }

    @Test
    @DisplayName("A-01 gate ON: BLOCK_ON_ERROR's untried siblings are ACKed and reported, in FIFO order, never the head")
    void blockOnErrorSettlesSiblingsWhenGateIsOn() {
        mediator.always("m0", MediationOutcome.ErrorConfig.rejected(500, "boom"));
        var reporter = new FakeSettledReporter();
        pool = new Pool(new Pool.Config("POOL-A", 2, 0), FAST, mediator, broker, metrics,
                Clock.systemUTC(), Warnings.NO_OP, new BlockedSiblings.Settle(reporter));

        pool.submit(ordered("g", "m0", DispatchMode.BLOCK_ON_ERROR));
        pool.submit(orderedWithToken("g", "m1", DispatchMode.BLOCK_ON_ERROR, "tok-1"));
        // No auth token: never came from the platform scheduler, so there is
        // no dispatch-job row for it — ACKed like any other sibling, but
        // skipped from the report.
        pool.submit(ordered("g", "m2", DispatchMode.BLOCK_ON_ERROR));
        pool.submit(orderedWithToken("g", "m3", DispatchMode.BLOCK_ON_ERROR, "tok-3"));

        await(() -> broker.acked.size() == 4);
        assertThat(broker.nacked).isEmpty();
        assertThat(broker.ackReasons.values()).containsOnly("rejected-group-blocked");
        assertThat(mediator.delivered).containsOnly("m0");

        await(() -> !reporter.reports.isEmpty());
        assertThat(reporter.reports).hasSize(1);
        var report = reporter.reports.getFirst();
        assertThat(report.poolCode()).isEqualTo("POOL-A");
        assertThat(report.group()).isEqualTo("g");
        // The head never appears; a tokenless sibling is skipped; the two
        // that qualify keep their FIFO buffer order.
        assertThat(report.jobs()).extracting(SettledJob::id).containsExactly("m1", "m3");
        assertThat(report.jobs()).extracting(SettledJob::token).containsExactly("tok-1", "tok-3");
    }

    @Test
    @DisplayName("A-01 gate ON: no report is sent when every sibling carries no auth token")
    void blockOnErrorReportsNothingWithNoTokenedSiblings() {
        mediator.always("m0", MediationOutcome.ErrorConfig.rejected(500, "boom"));
        var reporter = new FakeSettledReporter();
        pool = new Pool(new Pool.Config("POOL-A", 2, 0), FAST, mediator, broker, metrics,
                Clock.systemUTC(), Warnings.NO_OP, new BlockedSiblings.Settle(reporter));

        IntStream.range(0, 3).forEach(i -> pool.submit(ordered("g", "m" + i, DispatchMode.BLOCK_ON_ERROR)));

        await(() -> broker.acked.size() == 3);
        sleepBriefly();
        assertThat(reporter.reports).isEmpty();
    }

    @Test
    @DisplayName("NEXT_ON_ERROR never touches the settled reporter, gate on or off")
    void nextOnErrorNeverTouchesTheReporter() {
        mediator.always("m0", MediationOutcome.ErrorConfig.rejected(500, "boom"));
        mediator.answer("m1", MediationOutcome.Success.of(200));
        var reporter = new FakeSettledReporter();
        pool = new Pool(new Pool.Config("POOL-A", 2, 0), FAST, mediator, broker, metrics,
                Clock.systemUTC(), Warnings.NO_OP, new BlockedSiblings.Settle(reporter));

        pool.submit(ordered("g", "m0", DispatchMode.NEXT_ON_ERROR));
        pool.submit(orderedWithToken("g", "m1", DispatchMode.NEXT_ON_ERROR, "tok-1"));

        await(() -> broker.acked.size() == 2);
        sleepBriefly();
        assertThat(reporter.reports).isEmpty();
    }

    @Test
    @DisplayName("NEXT_ON_ERROR: the group continues past a rejected head after ONE attempt")
    void nextOnErrorContinuesAfterOneAttempt() {
        // The Q1 deviation: Go blocks the group for both ordered modes.
        mediator.always("m0", MediationOutcome.ErrorConfig.rejected(500, "boom"));
        mediator.answer("m1", MediationOutcome.Success.of(200));
        mediator.answer("m2", MediationOutcome.Success.of(200));
        var p = pool(2, 0);

        IntStream.range(0, 3).forEach(i -> p.submit(ordered("g", "m" + i, DispatchMode.NEXT_ON_ERROR)));

        await(() -> broker.acked.size() == 3);
        assertThat(mediator.attempts("m0")).isOne();
        assertThat(mediator.delivered).containsExactly("m0", "m1", "m2");
        assertThat(broker.ackReasons.get("m0")).isEqualTo("rejected-group-continues");
    }

    @Test
    @DisplayName("IMMEDIATE: a rejected message is ACKed after ONE attempt")
    void immediateRejectedAcksAfterOneAttempt() {
        mediator.always("m1", MediationOutcome.ErrorConfig.rejected(500, "boom"));

        pool(4, 0).submit(immediate("m1"));

        await(() -> broker.acked.contains("m1"));
        assertThat(mediator.attempts("m1")).isOne();
        assertThat(metrics.failures.get()).isOne();
        assertThat(broker.ackReasons.get("m1")).isEqualTo("rejected");
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
    @DisplayName("an ungrouped flushGroup is ignored rather than suppressing the empty bucket, and it is logged")
    void ungroupedFlushIsIgnored() {
        // §4.5: "Ungrouped is a no-op" MUST be logged, not silently ignored —
        // otherwise a target's misuse of the flag is invisible from outside
        // the process.
        var log = (Logger) LoggerFactory.getLogger(Pool.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            mediator.answer("m1", MediationOutcome.Success.flushing(200, 60));
            mediator.answer("m2", MediationOutcome.Success.of(200));
            var p = pool(4, 0);

            p.submit(immediate("m1"));
            await(() -> broker.acked.contains("m1"));
            p.submit(immediate("m2"));

            await(() -> broker.acked.contains("m2"));
            assertThat(metrics.suppressed.get()).isZero();
            assertThat(captured.list)
                    .as("an ungrouped flushGroup must be logged, not silently dropped")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("flushGroup ignored");
                        // The id is a queryable field now, not text inside the
                        // message (CONVENTIONS §10) — assert it where it lives.
                        assertThat(event.getKeyValuePairs())
                                .anySatisfy(kv -> {
                                    assertThat(kv.key).isEqualTo("message_id");
                                    assertThat(kv.value).isEqualTo("m1");
                                });
                    });
        } finally {
            log.detachAppender(captured);
        }
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
    @DisplayName("the pool's own rate limiter raises exactly one RATE_LIMIT warning for a burst of limited deliveries")
    void rateLimitWarnsOnceForARun() {
        // A burst that keeps observing the limiter as holding messages back
        // must not flood the warning store — one INFO entry for the
        // transition into limiting, not one per limited delivery.
        //
        // Deterministic shape: the FIRST delivery goes through unlimited
        // (rate 1/min, one token) and is allowed to finish before the burst,
        // so the "proceeded unlimited → re-arm the warning" reset cannot race
        // the burst's own warning. The burst is then 19 deliveries that are
        // all limited, so exactly one transition into limiting can occur.
        IntStream.range(0, 20).forEach(i -> mediator.answer("m" + i, MediationOutcome.Success.of(200)));
        var raised = new CopyOnWriteArrayList<String>();
        Warnings warnings = (severity, category, message) -> raised.add(severity + "/" + category);
        pool = new Pool(new Pool.Config("POOL-A", 20, 1), FAST, mediator, broker, metrics,
                Clock.systemUTC(), warnings);

        pool.submit(immediate("m0"));
        await(() -> broker.acked.contains("m0"));
        IntStream.range(1, 20).forEach(i -> pool.submit(immediate("m" + i)));

        // At least five DIFFERENT deliveries must have observed the limiter
        // as limited — a counter that must change — AND the warning must
        // have landed (it is raised after the counter moves, on the same
        // worker; awaiting only the counter raced it), proving the single
        // warning survived repeated observations, not just one lucky check.
        await(() -> metrics.rateLimited.get() >= 5 && raised.contains("INFO/RATE_LIMIT"));
        assertThat(raised.stream().filter("INFO/RATE_LIMIT"::equals).count())
                .as("one warning for the burst, not one per limited delivery [DIAG rateLimited=%d acked=%d nacked=%d queueSize=%d active=%d success=%d limited=%s raised=%s]",
                        metrics.rateLimited.get(), broker.acked.size(), broker.nacked.size(), pool.queueSize(),
                        pool.activeWorkers(), metrics.successes.get(), pool.rateLimited(), raised)
                .isEqualTo(1);
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

    // ── Unit 4b: drain, blocked-groups snapshot ────────────────────────────

    @Test
    @DisplayName("X-11: drain() stops admitting but lets buffered/in-flight work finish; drained() reports when it has")
    void drainStopsAdmittingButFinishesBufferedWork() {
        mediator.block();
        var p = pool(2, 0);
        p.submit(ordered("g", "m0", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("g", "m1", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("g", "m2", DispatchMode.BLOCK_ON_ERROR));
        await(() -> mediator.inFlight.get() == 1); // the head has grabbed the mediator

        p.drain();

        assertThat(p.drained()).as("still holding the head plus two buffered siblings").isFalse();
        p.submit(ordered("g", "late", DispatchMode.BLOCK_ON_ERROR));
        assertThat(broker.nacked).as("a submission after drain() is rejected, not queued").containsKey("late");

        mediator.unblock();

        await(() -> broker.acked.size() == 3);
        assertThat(mediator.delivered)
                .as("the whole buffer kept draining after drain(), in order")
                .containsExactly("m0", "m1", "m2");
        await(p::drained);
    }

    @Test
    @DisplayName("R-04: groupSnapshot reports each group's depth/draining state, joined with the pool's flush suppression")
    void groupSnapshotJoinsDepthDrainingAndSuppression() {
        mediator.block();
        var p = pool(4, 0);
        p.submit(ordered("alpha", "a-head", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("alpha", "a1", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("alpha", "a2", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("alpha", "a3", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("beta", "b-head", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("beta", "b1", DispatchMode.BLOCK_ON_ERROR));
        await(() -> mediator.inFlight.get() == 2); // both heads grabbed, each by its own drainer

        var snapshot = p.groupSnapshot();
        assertThat(snapshot).hasSize(2);
        var alpha = snapshot.stream().filter(g -> g.group().equals("alpha")).findFirst().orElseThrow();
        var beta = snapshot.stream().filter(g -> g.group().equals("beta")).findFirst().orElseThrow();

        assertThat(alpha.depth()).as("the head is popped; three siblings sit behind it").isEqualTo(3);
        assertThat(alpha.draining()).isTrue();
        assertThat(alpha.suppressedUntil()).as("never flushed").isNull();
        assertThat(beta.depth()).isOne();
        assertThat(beta.draining()).isTrue();

        p.flushRegistry().flush("beta", Duration.ofMinutes(5));
        var expiry = p.flushRegistry().suppressedUntil("beta").orElseThrow();
        var afterFlush = p.groupSnapshot().stream().filter(g -> g.group().equals("beta")).findFirst().orElseThrow();
        assertThat(afterFlush.suppressedUntil()).as("joined with the pool's own flush registry").isEqualTo(expiry);

        mediator.unblock();
        await(() -> broker.acked.size() == 6);
    }

    @Test
    @DisplayName("a submit racing pool close does not silently lose the message (IMMEDIATE branch)")
    void submitRacingCloseNacksRatherThanLosingTheMessage() throws Exception {
        // Simulates close()/drain()-then-close() landing between submit()'s
        // stopped/draining check and workers.execute(): the executor is shut
        // down directly, underneath the still-open stopped/draining flags,
        // so submit() reaches start() and workers.execute() throws
        // RejectedExecutionException exactly as the real race would —
        // deterministic, rather than trying to win an actual race.
        var p = pool(4, 0);
        var workersField = Pool.class.getDeclaredField("workers");
        workersField.setAccessible(true);
        var workers = (java.util.concurrent.ExecutorService) workersField.get(p);
        workers.shutdownNow();

        p.submit(immediate("raced"));

        assertThat(broker.nacked).as("nacked, not silently dropped — neither acked, nacked nor released before").containsKey("raced");
        assertThat(broker.nackReasons.get("raced")).isEqualTo("pool-closed");
        assertThat(p.queueSize()).as("immediateWaiting must be decremented back, or this leaks forever").isZero();
    }

    // ── Unit 3: layer-2 dedup, drainer resurrection ────────────────────────

    @Test
    @DisplayName("layer 2 (`docs/spec/router.md` §2.1 EnsureTracked): a broker that no longer owns the "
            + "message acks it as a duplicate without delivering")
    void deliverOnceAcksADuplicateWithoutDelivering() {
        // A different broker copy has since claimed the same application id
        // (the route-time entry was reaped while this message sat buffered).
        // This attempt must not deliver — a live copy elsewhere already owns
        // the pipeline.
        broker.owns = false;
        var p = pool(4, 0);

        p.submit(immediate("m1"));

        await(() -> broker.acked.contains("m1"));
        assertThat(broker.ackReasons.get("m1")).isEqualTo("duplicate");
        assertThat(mediator.attempts("m1"))
                .as("the mediator must never be called for a message this broker copy no longer owns")
                .isZero();
    }

    @Test
    @DisplayName("resumeGroup drains a group whose drainer died leaving work buffered (`docs/spec/router.md` §2.1)")
    void resumeGroupDrainsAGroupWhoseDrainerDied() {
        var delivered = new CopyOnWriteArrayList<String>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        // Simulates a drainer thread dying mid-backoff (an interrupted slot
        // wait or a cancelled backoff both take this same path in
        // Pool#sleepBackoff / #runDrainer): the message is re-fronted and the
        // drainer flag is released, but the buffer is NOT emptied — nothing
        // is draining the group any more.
        Mediator selfInterrupting = (message, recordFailure) -> {
            delivered.add(message.id());
            if ("m0".equals(message.id()) && interrupted.compareAndSet(false, true)) {
                Thread.currentThread().interrupt();
                return new MediationOutcome.RateLimited(1);
            }
            return MediationOutcome.Success.of(200);
        };
        var slowBackoff = new Pool.Backoffs(
                new RetryPolicy(List.of(Duration.ofSeconds(60)), Duration.ofSeconds(60), Duration.ofSeconds(60), 12),
                new RetryPolicy(List.of(), Duration.ofSeconds(60), Duration.ofSeconds(60), 12));
        pool = new Pool(new Pool.Config("POOL-A", 4, 0), slowBackoff, selfInterrupting, broker, metrics,
                Clock.systemUTC());

        pool.submit(ordered("g", "m0", DispatchMode.BLOCK_ON_ERROR));
        pool.submit(ordered("g", "m1", DispatchMode.BLOCK_ON_ERROR));

        // The one attempt at m0 self-interrupted mid-backoff; both messages
        // sit buffered and nothing is draining them.
        await(() -> interrupted.get());
        await(() -> pool.queueSize() == 2);
        sleepBriefly();
        assertThat(pool.queueSize()).as("still buffered — the drainer died, it did not finish").isEqualTo(2);
        assertThat(broker.acked).isEmpty();

        pool.resumeGroup("g");

        await(() -> broker.acked.size() == 2);
        assertThat(delivered).as("FIFO order survives the resurrection").containsExactly("m0", "m0", "m1");
        assertThat(pool.queueSize()).isZero();
    }

    @Test
    @DisplayName("resumeGroup does not start a second drainer for a group that already has a live one")
    void resumeGroupIsANoOpWhileADrainerIsActive() {
        mediator.block();
        var p = pool(4, 0);
        p.submit(ordered("g", "m0", DispatchMode.BLOCK_ON_ERROR));
        p.submit(ordered("g", "m1", DispatchMode.BLOCK_ON_ERROR));
        // One drainer, actively delivering the head.
        await(() -> mediator.inFlight.get() == 1);

        p.resumeGroup("g");

        // claimDrainer must refuse: a live drainer already owns this group.
        // A second one would let m0 and m1 deliver concurrently, breaking
        // the FIFO guarantee ordered delivery exists for.
        sleepBriefly();
        assertThat(mediator.inFlight.get()).isEqualTo(1);

        mediator.unblock();
        await(() -> broker.acked.size() == 2);
        assertThat(mediator.delivered).containsExactly("m0", "m1");
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
        return message(id, null, DispatchMode.IMMEDIATE, null);
    }

    private static QueuedMessage ordered(String group, String id, DispatchMode mode) {
        return message(id, group, mode, null);
    }

    /// A sibling carrying a platform-signed auth token — what makes it a
    /// dispatch job the settled hook has something to report.
    private static QueuedMessage orderedWithToken(String group, String id, DispatchMode mode, String authToken) {
        return message(id, group, mode, authToken);
    }

    private static QueuedMessage message(String id, String group, DispatchMode mode, String authToken) {
        return QueuedMessage.of(
                new Message(id, "", authToken, null, MediationType.HTTP, "https://x.test/h", group, false, mode),
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
        /// Layer 2 dedup (`owns`): true unless a test says otherwise. A test
        /// flips this to simulate a rival broker copy having claimed the
        /// message since route time.
        volatile boolean owns = true;
        final List<String> acked = new CopyOnWriteArrayList<>();
        final Map<String, String> ackReasons = new ConcurrentHashMap<>();
        final Map<String, Duration> nacked = new ConcurrentHashMap<>();
        final Map<String, String> nackReasons = new ConcurrentHashMap<>();
        final InFlightTracker tracker = new InFlightTracker(Clock.systemUTC());

        @Override
        public void ack(QueuedMessage message) {
            acked.add(message.id());
            tracker.remove(message.id());
        }

        @Override
        public boolean owns(QueuedMessage message) {
            return owns;
        }

        @Override
        public void ack(QueuedMessage message, String reason) {
            ackReasons.put(message.id(), reason);
            ack(message);
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
        public void nack(QueuedMessage message, Duration delay, String reason) {
            nackReasons.put(message.id(), reason);
            nack(message, delay);
        }

        @Override
        public void release(QueuedMessage message) {
            tracker.remove(message.id());
        }
    }

    /// Captures every [SettledReport] handed to it, synchronously — a fake
    /// has no fire-and-forget obligation to honour, unlike [SettledReporter]'s
    /// production implementation.
    private static final class FakeSettledReporter implements SettledReporter {
        final List<SettledReport> reports = new CopyOnWriteArrayList<>();

        @Override
        public void report(SettledReport report) {
            reports.add(report);
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

        @Override
        public void recordHttpVersion(HttpVersion version) {
        }
    }
}

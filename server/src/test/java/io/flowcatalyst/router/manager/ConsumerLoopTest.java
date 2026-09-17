package io.flowcatalyst.router.manager;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.router.observability.Warnings;
import org.slf4j.LoggerFactory;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Mediator;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// The poll loop's pacing and stop behaviour (`docs/spec/router.md` §3.2).
class ConsumerLoopTest {

    private final Clock clock = Clock.systemUTC();
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final RecordingWarnings warnings = new RecordingWarnings();
    private final ScriptedConsumer consumer = new ScriptedConsumer("queue-1");
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final AtomicBoolean deliveryBlocked = new AtomicBoolean();

    /// Keeps submitting so the pool stays at capacity; stopped after each test.
    private Thread topUp;
    private Pool pool;
    /// Second pool for the per-consumer capacity tests: fed by nobody, so it
    /// stays free even while [#pool] (named "A" there) is full — the
    /// discriminating case between "any pool has room" and "the pools THIS
    /// consumer's own batch fed have room".
    private Pool poolB;
    private Thread loopThread;

    /// Every manager a test built, so its synthesised pools are closed too.
    private final List<RouterManager> managers = new CopyOnWriteArrayList<>();

    private RouterManager manager() {
        Mediator mediator = (message, recordFailure) -> {
            // Held open so a test can fill the pool: with instant delivery the
            // queue drains as fast as it fills and never reaches capacity.
            while (deliveryBlocked.get()) {
                Thread.sleep(Duration.ofMillis(5));
            }
            delivered.add(message.id());
            return MediationOutcome.Success.of(200);
        };
        pool = new Pool(new Pool.Config(RouterManager.DEFAULT_POOL, 4, 0), mediator, NO_OP_BROKER,
                PoolMetrics.NO_OP, clock);
        var manager = new RouterManager(tracker, warnings, clock,
                config -> new Pool(config, mediator, NO_OP_BROKER, PoolMetrics.NO_OP, clock));
        manager.registerPool(RouterManager.DEFAULT_POOL, pool);
        manager.registerConsumer(consumer);
        managers.add(manager);
        return manager;
    }

    /// Two pools, "A" and "B", both registered up front — for the
    /// per-consumer capacity tests, which need a pool the consumer feeds
    /// (filled to capacity) and a pool it never touches (left with room).
    private RouterManager twoPoolManager() {
        Mediator mediator = (message, recordFailure) -> {
            while (deliveryBlocked.get()) {
                Thread.sleep(Duration.ofMillis(5));
            }
            delivered.add(message.id());
            return MediationOutcome.Success.of(200);
        };
        pool = new Pool(new Pool.Config("A", 4, 0), mediator, NO_OP_BROKER, PoolMetrics.NO_OP, clock);
        poolB = new Pool(new Pool.Config("B", 4, 0), mediator, NO_OP_BROKER, PoolMetrics.NO_OP, clock);
        var manager = new RouterManager(tracker, warnings, clock,
                config -> new Pool(config, mediator, NO_OP_BROKER, PoolMetrics.NO_OP, clock));
        manager.registerPool("A", pool);
        manager.registerPool("B", poolB);
        manager.registerConsumer(consumer);
        managers.add(manager);
        return manager;
    }

    @AfterEach
    void stopLoop() {
        if (loopThread != null) {
            loopThread.interrupt();
        }
        if (topUp != null) {
            topUp.interrupt();
        }
        deliveryBlocked.set(false);
        managers.forEach(RouterManager::close);
        if (pool != null) {
            pool.close();
        }
        if (poolB != null) {
            poolB.close();
        }
    }

    private ConsumerLoop start(RouterManager manager) {
        var loop = new ConsumerLoop(consumer, manager, warnings, clock);
        loopThread = Thread.ofVirtual().start(loop);
        return loop;
    }

    @Test
    @DisplayName("polled messages reach a pool")
    void messagesReachAPool() {
        consumer.deliver(batch("m1", "m2"));
        start(manager());

        await(() -> delivered.containsAll(List.of("m1", "m2")));
    }

    @Test
    @DisplayName("a successful poll heartbeats even when it returned nothing")
    void emptyPollStillHeartbeats() {
        // An idle queue is alive. Withholding the heartbeat would make the
        // stall detector treat quiet as stuck.
        consumer.deliver(List.of());
        var loop = start(manager());

        await(() -> loop.lastPoll().isPresent());
    }

    @Test
    @DisplayName("a failed poll does not heartbeat, so a broken queue looks broken")
    void failedPollDoesNotHeartbeat() {
        consumer.failAlwaysWith(new IllegalStateException("broker unreachable"));
        var loop = start(manager());

        await(() -> consumer.polls.get() >= 2);
        assertThat(loop.lastPoll())
                .as("a queue whose polls are failing must not report itself alive")
                .isEmpty();
    }

    @Test
    @DisplayName("a failed poll is retried rather than ending the loop")
    void failedPollKeepsGoing() {
        consumer.failOnceWith(new IllegalStateException("transient"));
        consumer.deliver(batch("m1"));
        start(manager());

        await(() -> delivered.contains("m1"));
    }

    @Test
    @DisplayName("§7.3: a run of failing polls raises exactly one CONNECTION warning, not one per attempt")
    void failingPollStreakWarnsOnce() {
        // Mirrors the POOL_CAPACITY transition rule: the warning marks
        // entering the failing state, not every tick spent in it — otherwise
        // a broker outage floods the warning store with one entry per second.
        consumer.failAlwaysWith(new IllegalStateException("broker unreachable"));
        start(manager());

        await(() -> consumer.polls.get() >= 3);

        assertThat(warnings.raised).hasSize(1);
        assertThat(warnings.raised.getFirst())
                .contains("WARNING").contains("CONNECTION").contains("queue-1").contains("broker unreachable");
    }

    @Test
    @DisplayName("§7.3: a run of failing polls logs one stack trace, not one per attempt")
    void failingPollStreakLogsTheCauseOnce() {
        // Same transition rule as the CONNECTION warning above, applied to the
        // log record: the stack trace marks *entering* the failing state. An
        // unreachable broker fails every poll for as long as it is down, and a
        // trace per poll is volume, not information — but every attempt must
        // still be logged, and must still say what failed.
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        var loopLog = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ConsumerLoop.class);
        loopLog.addAppender(captured);
        try {
            consumer.failAlwaysWith(new IllegalStateException("broker unreachable"));
            start(manager());

            await(() -> consumer.polls.get() >= 3);

            var failures = captured.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("poll failed"))
                    .toList();
            assertThat(failures).as("every failing poll is still logged").hasSizeGreaterThanOrEqualTo(3);
            assertThat(failures.stream().filter(e -> e.getThrowableProxy() != null).toList())
                    .as("exactly one stack trace for the streak")
                    .hasSize(1);
            assertThat(failures.getFirst().getThrowableProxy()).as("and it is the first").isNotNull();
            assertThat(failures.stream().skip(1).toList())
                    .as("the rest still name the failure, without the trace")
                    .allSatisfy(e -> assertThat(e.getKeyValuePairs().stream().map(kv -> String.valueOf(kv.value)).toList())
                            .anySatisfy(v -> assertThat(v).contains("broker unreachable")));
        } finally {
            loopLog.detachAppender(captured);
        }
    }

    @Test
    @DisplayName("§7.3: the first successful poll after a failing streak raises an INFO CONNECTION recovery notice")
    void connectionRecoveryRaisesInfo() {
        consumer.failOnceWith(new IllegalStateException("blip"));
        consumer.deliver(batch("m1"));
        start(manager());

        await(() -> delivered.contains("m1"));
        await(() -> warnings.raised.size() >= 2);

        assertThat(warnings.raised.get(0)).contains("WARNING").contains("CONNECTION");
        assertThat(warnings.raised.get(1)).contains("INFO").contains("CONNECTION").contains("queue-1");
    }

    @Test
    @DisplayName("a stopped consumer ends its loop instead of spinning")
    void stoppedConsumerEndsTheLoop() {
        // Terminal: the restart watchdog rebuilds the consumer, and the loop
        // does not try to resurrect itself.
        consumer.stop();
        start(manager());

        await(() -> !loopThread.isAlive());
        assertThat(warnings.raised).isEmpty();
    }

    @Test
    @DisplayName("owner ruling 2026-09-11: a poll answering QueueMissing ends the loop, detaches the consumer, and raises no CONNECTION warning")
    void queueMissingEndsTheLoopAndDetachesWithoutWarning() {
        // Mutant: make ConsumerLoop treat QueueMissing like an ordinary poll
        // failure (or fall into `default` and keep looping) → either this
        // loop never stops, or `warnings.raised` gains a CONNECTION entry —
        // both fail the assertions below.
        consumer.answerQueueMissingOnNextPoll();
        var manager = manager();
        start(manager);

        await(() -> !loopThread.isAlive());

        assertThat(warnings.raised).as("a missing queue is not a connection failure").isEmpty();
        assertThat(manager.activeConsumer("queue-1"))
                .as("detached exactly as a reconfigure's stopConsumer would do").isEmpty();
        // Still resolvable for ack/nack via the lingering set — the same
        // treatment stopConsumer gives a queue a reconfigure removes.
        assertThat(manager.consumer("queue-1")).isPresent();
    }

    @Test
    @DisplayName("the loop stops on interruption and restores the flag")
    void interruptionStopsTheLoop() {
        consumer.deliver(List.of());
        start(manager());
        await(() -> consumer.polls.get() >= 1);

        loopThread.interrupt();

        await(() -> !loopThread.isAlive());
    }

    @Test
    @DisplayName("with every pool full the loop pauses instead of pulling messages it must hand back")
    void pausesWhenAllPoolsAreFull() {
        var manager = manager();
        fillPool(manager);
        consumer.deliver(batch("m1"));

        start(manager);

        await(() -> !warnings.raised.isEmpty());
        assertThat(warnings.raised.getFirst())
                .contains("POOL_CAPACITY").contains("all pools at capacity").contains("queue-1");
        assertThat(consumer.polls.get()).as("no poll while there is nowhere to put the result").isZero();
    }

    @Test
    @DisplayName("the capacity warning fires on the transition, not once per pause")
    void capacityWarningIsNotRepeated() {
        // A warning store holding a thousand entries would otherwise be
        // flooded by one busy period.
        var manager = manager();
        fillPool(manager);
        start(manager);
        await(() -> !warnings.raised.isEmpty());

        sleep(Duration.ofMillis(2_500)); // well past the old fixed pause

        assertThat(warnings.raised).hasSize(1);
    }

    @Test
    @DisplayName("2026-09-07: capacity returning wakes the loop within 100 ms, not after a fixed pause")
    void resumesPromptlyWhenCapacityReturns() {
        // Mutant: put `Thread.sleep(ALL_FULL_PAUSE)` back in place of the
        // park in ConsumerLoop#awaitCapacity → this test fails on the
        // resume-timing assertion below, because the loop would still be
        // asleep 100 ms after the pool frees up.
        var manager = manager();
        fillPool(manager);
        consumer.deliver(batch("m1"));
        start(manager);

        await(() -> !warnings.raised.isEmpty());
        int pollsWhileFull = consumer.polls.get();

        // Parked, not spinning: confirms the loop is actually waiting on the
        // gate rather than busy-polling while every pool is full.
        sleep(Duration.ofMillis(300));
        assertThat(consumer.polls.get())
                .as("no poll should happen while every pool stays full")
                .isEqualTo(pollsWhileFull);

        // Free the pool: stop the filler and let the blocked deliveries
        // through, so queueSize drops back under the threshold.
        long freedAt = System.nanoTime();
        topUp.interrupt();
        deliveryBlocked.set(false);

        await(() -> consumer.polls.get() > pollsWhileFull);
        var elapsed = Duration.ofNanos(System.nanoTime() - freedAt);

        // Measuring "promptly" needs a wall clock, and a wall clock on a
        // starved machine measures the machine. This assertion failed twice
        // under a concurrent Maven build and never idle, so the budget is
        // calibrated against *this* machine right now — the cost of a bare
        // virtual-thread handoff — and the test declines to judge when even
        // that cannot be measured. The budget stays far below the fixed pause
        // it exists to exclude (POLL_ERROR_PAUSE is 1s), so the mutant in the
        // comment above still dies.
        Duration handoff = handoffLatency();
        Assumptions.assumeTrue(handoff.compareTo(Duration.ofMillis(50)) < 0,
                "machine too loaded to time a resume: a bare virtual-thread handoff took " + handoff);
        Duration budget = min(max(Duration.ofMillis(100), handoff.multipliedBy(10)), Duration.ofMillis(500));

        assertThat(elapsed)
                .as("the loop must resume on the capacity signal, not wait out a fixed pause "
                        + "(budget %s, calibrated from a %s handoff)", budget, handoff)
                .isLessThan(budget);
    }

    @Test
    @DisplayName("2026-09-07: stopping a loop parked for capacity returns promptly, not after a fixed pause")
    void stopsPromptlyWhileParkedForCapacity() {
        var manager = manager();
        fillPool(manager);
        start(manager);
        await(() -> !warnings.raised.isEmpty());

        long stoppedAt = System.nanoTime();
        loopThread.interrupt();
        await(() -> !loopThread.isAlive());
        var elapsed = Duration.ofNanos(System.nanoTime() - stoppedAt);

        assertThat(elapsed)
                .as("interrupting a loop parked on the capacity gate must not wait out a fixed pause")
                .isLessThan(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("owner ruling 2026-09-07: neither a full nor a partial batch pauses before the next poll")
    void batchesRepollImmediately() {
        // A full batch (evidently more work) and a partial one (which used
        // to pause 500 ms on the theory the queue was draining) now behave
        // identically — the second batch below is partial (one message),
        // exactly the case the removed pause used to slow down.
        consumer.deliver(batch(IntStream.range(0, ConsumerLoop.MAX_POLL)
                .mapToObj(i -> "m" + i).toArray(String[]::new)));
        consumer.deliver(batch("second-batch"));
        start(manager());

        long startedAt = System.nanoTime();
        await(() -> delivered.contains("second-batch"));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("a partial batch must not pause before the next poll")
                .isLessThan(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("§2.4/§6: a consumer whose last batch fed a full pool pauses even though an unrelated pool has room")
    void pausesForItsOwnFedPoolEvenWhenAnOtherPoolHasRoom() {
        // The defect the per-consumer rule fixes: judged process-wide (any
        // pool has room), this consumer would never pause here, because B —
        // which it never feeds — always has capacity. Mutate #hasRoom back
        // to `manager.anyPoolHasCapacity()` and this test fails: the
        // warning below never fires and the assertion times out.
        var manager = twoPoolManager();
        consumer.deliver(List.of(message("seed", "A")));
        var loop = start(manager);
        await(() -> delivered.contains("seed"));
        // The loop's last (only) non-empty batch fed pool "A" — its
        // remembered fed-pool set is now {"A"}.

        fillPoolA(manager);
        // Further polls return nothing; lastFedPools is untouched by an
        // empty batch, so it keeps naming "A".
        consumer.deliver(List.of());

        await(() -> !warnings.raised.isEmpty());
        assertThat(warnings.raised.getFirst())
                .contains("POOL_CAPACITY").contains("queue-1");
        // The discriminating assertion: the router as a WHOLE still has
        // capacity (via B), so a pause here can only be explained by the
        // per-consumer rule, not the process-wide one it replaced.
        assertThat(manager.anyPoolHasCapacity())
                .as("pool B, which this consumer never fed, still has room").isTrue();
        assertThat(loop.lastAlive()).as("a capacity pause is alive, not stalled").isPresent();
    }

    // ── Liveness while a poll is genuinely in progress (2026-09-07) ──────
    //
    // `docs/spec/router.md` §3.2, §5 row 47: `NatsQueue#poll` now blocks
    // untimed on its continuous subscription's buffer, so an idle queue and
    // a hung one both look like "poll has not returned in a while" to
    // anything that only watches `lastPoll`. These pin the fix —
    // `ConsumerLoop#lastAlive` consulting `Consumer#lastBrokerActivity`
    // while a poll is in progress — at the level that actually decides a
    // restart: `ConsumerSupervisor#stalled(ConsumerLoop)`, the method
    // `RouterServer#restartStalledLoops` calls on every housekeeping tick.

    @Test
    @DisplayName("2026-09-07: a poll blocked on a live broker is alive on its broker-activity signal, and is not restarted")
    void pollInProgressWithRecentBrokerActivityIsNotStalled() throws InterruptedException {
        // Mutant: delete the `pollInProgress`/`lastBrokerActivity` branch
        // from ConsumerLoop#lastAlive → this test fails. Without it,
        // lastAlive() falls back to the FIRST poll's timestamp (seeded
        // before the clock jump below) as the only heartbeat this consumer
        // will ever report while its second poll stays blocked, so the
        // supervisor reads it as 61s stale and restarts a consumer that is
        // in fact still hearing from the broker.
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var localWarnings = new RecordingWarnings();
        var localTracker = new InFlightTracker(mutableClock);
        var localManager = new RouterManager(localTracker, localWarnings, mutableClock,
                cfg -> new Pool(cfg, (msg, rf) -> MediationOutcome.Success.of(200), NO_OP_BROKER,
                        PoolMetrics.NO_OP, mutableClock));
        localManager.registerPool(RouterManager.DEFAULT_POOL,
                new Pool(new Pool.Config(RouterManager.DEFAULT_POOL, 4, 0),
                        (msg, rf) -> MediationOutcome.Success.of(200), NO_OP_BROKER, PoolMetrics.NO_OP, mutableClock));
        managers.add(localManager);
        var brokerConsumer = new BlockingBrokerConsumer("queue-broker");
        localManager.registerConsumer(brokerConsumer);
        var loop = new ConsumerLoop(brokerConsumer, localManager, localWarnings, mutableClock);
        var thread = Thread.ofVirtual().start(loop);
        try {
            await(() -> brokerConsumer.polls.get() >= 1); // seeds lastPoll at T0
            await(() -> brokerConsumer.polls.get() >= 2); // now blocked in the SECOND poll

            mutableClock.advance(ConsumerSupervisor.STALL_THRESHOLD.plusSeconds(1));
            // The broker is still delivering (or idle-heartbeating) right
            // now, under the ADVANCED clock — even though this poll() CALL
            // has itself been "running" far longer than the stall
            // threshold.
            brokerConsumer.setBrokerActivity(mutableClock.instant());

            assertThat(loop.lastAlive()).hasValueSatisfying(last -> assertThat(Duration.between(last, mutableClock.instant()))
                    .as("recent broker activity makes the loop's liveness recent, not 61s stale")
                    .isLessThan(ConsumerSupervisor.STALL_THRESHOLD));

            var supervisor = new ConsumerSupervisor(localWarnings, mutableClock, Duration.ofMillis(1));
            assertThat(supervisor.stalled(loop))
                    .as("a poll in progress with recent broker activity must not be flagged for restart").isFalse();
        } finally {
            brokerConsumer.release();
            thread.interrupt();
            thread.join(Duration.ofSeconds(2).toMillis());
        }
    }

    @Test
    @DisplayName("2026-09-07: a poll blocked with STALE broker activity is genuinely hung, and IS flagged for restart")
    void pollInProgressWithStaleBrokerActivityIsStalled() throws InterruptedException {
        // The companion case: broker-activity awareness must not blanket-
        // suppress restarts. A poll that has been in progress past the
        // threshold with NO fresher broker evidence than its own stale seed
        // is exactly the "poll is hung" case the watchdog exists for.
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var localWarnings = new RecordingWarnings();
        var localTracker = new InFlightTracker(mutableClock);
        var localManager = new RouterManager(localTracker, localWarnings, mutableClock,
                cfg -> new Pool(cfg, (msg, rf) -> MediationOutcome.Success.of(200), NO_OP_BROKER,
                        PoolMetrics.NO_OP, mutableClock));
        localManager.registerPool(RouterManager.DEFAULT_POOL,
                new Pool(new Pool.Config(RouterManager.DEFAULT_POOL, 4, 0),
                        (msg, rf) -> MediationOutcome.Success.of(200), NO_OP_BROKER, PoolMetrics.NO_OP, mutableClock));
        managers.add(localManager);
        var brokerConsumer = new BlockingBrokerConsumer("queue-broker");
        localManager.registerConsumer(brokerConsumer);
        var loop = new ConsumerLoop(brokerConsumer, localManager, localWarnings, mutableClock);
        var thread = Thread.ofVirtual().start(loop);
        try {
            await(() -> brokerConsumer.polls.get() >= 1);
            await(() -> brokerConsumer.polls.get() >= 2); // now blocked in the second poll
            var staleActivity = mutableClock.instant();
            brokerConsumer.setBrokerActivity(staleActivity); // never refreshed again

            mutableClock.advance(ConsumerSupervisor.STALL_THRESHOLD.plusSeconds(1));

            var supervisor = new ConsumerSupervisor(localWarnings, mutableClock, Duration.ofMillis(1));
            assertThat(supervisor.stalled(loop))
                    .as("no broker evidence newer than the stale seed — a genuinely hung poll must still restart")
                    .isTrue();
        } finally {
            brokerConsumer.release();
            thread.interrupt();
            thread.join(Duration.ofSeconds(2).toMillis());
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private static final Broker NO_OP_BROKER = new Broker() {
        @Override
        public void ack(QueuedMessage message) {
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

    /// Keeps the pool at capacity for as long as the test needs it.
    ///
    /// A single fill cannot hold: a worker that acquires a slot decrements
    /// the waiting count, so with concurrency N the queue drops N below
    /// capacity the moment workers engage and `anyPoolHasCapacity()` goes
    /// true again. The "all pools full" state is only durable while messages
    /// keep arriving — which is exactly what makes it worth pausing for in
    /// production, and what this reproduces.
    ///
    /// An earlier version filled once and raced; it failed roughly one run in
    /// four, and was twice misread as build contention.
    private void fillPool(RouterManager manager) {
        deliveryBlocked.set(true);
        topUp = Thread.ofVirtual().start(() -> {
            int n = 0;
            while (!Thread.currentThread().isInterrupted()) {
                if (manager.anyPoolHasCapacity()) {
                    pool.submit(message("filler-" + n++));
                } else {
                    Thread.onSpinWait();
                }
            }
        });
        await(() -> !manager.anyPoolHasCapacity());
    }

    /// As [#fillPool], but fills only pool "A" (via [#twoPoolManager]),
    /// leaving pool "B" empty — the per-consumer capacity tests' fixture.
    private void fillPoolA(RouterManager manager) {
        deliveryBlocked.set(true);
        topUp = Thread.ofVirtual().start(() -> {
            int n = 0;
            while (!Thread.currentThread().isInterrupted()) {
                if (manager.poolsHaveCapacity(java.util.Set.of("A"))) {
                    pool.submit(message("filler-" + n++, "A"));
                } else {
                    Thread.onSpinWait();
                }
            }
        });
        await(() -> !manager.poolsHaveCapacity(java.util.Set.of("A")));
    }

    private static List<QueuedMessage> batch(String... ids) {
        return java.util.Arrays.stream(ids).map(ConsumerLoopTest::message).toList();
    }

    private static QueuedMessage message(String id, String poolCode) {
        return QueuedMessage.of(
                new Message(id, poolCode, null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.IMMEDIATE),
                "broker-" + id, "receipt-" + id, "queue-1");
    }

    private static QueuedMessage message(String id) {
        return QueuedMessage.of(
                new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.IMMEDIATE),
                "broker-" + id, "receipt-" + id, "queue-1");
    }

    /// Liveness, not performance: a healthy run returns as soon as the
    /// condition holds, so a generous deadline costs nothing and only changes
    /// how long a stuck test takes to report. It was 10s, and
    /// `resumesPromptlyWhenCapacityReturns` failed on it twice under machine
    /// load while passing every idle run — 10s was measuring the machine.
    private static final Duration AWAIT_BUDGET = Duration.ofSeconds(60);

    /// The cost of waking one parked virtual thread on this machine, right
    /// now: park, signal, measure, best of five. On an idle machine this is
    /// well under a millisecond; under contention it climbs, and it climbs
    /// for the same reason a signalled consumer loop is slow to resume — so
    /// it is the right yardstick for [#resumesPromptlyWhenCapacityReturns].
    private static Duration handoffLatency() {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            var release = new CountDownLatch(1);
            var woke = new CountDownLatch(1);
            var at = new AtomicLong();
            Thread.ofVirtual().start(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                at.set(System.nanoTime());
                woke.countDown();
            });
            sleep(Duration.ofMillis(20));   // let it park
            long signalled = System.nanoTime();
            release.countDown();
            try {
                if (!woke.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    return Duration.ofSeconds(5);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            best = Math.min(best, at.get() - signalled);
        }
        return Duration.ofNanos(best);
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_BUDGET.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(5));
        }
        throw new AssertionError("condition not met within " + AWAIT_BUDGET);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /// A consumer that answers from a script, then blocks politely.
    private static final class ScriptedConsumer implements Consumer {
        private final String id;
        private final Deque<List<QueuedMessage>> batches = new ArrayDeque<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile RuntimeException failure;
        private volatile boolean failForever;
        private volatile boolean answerQueueMissingNext;
        final AtomicInteger polls = new AtomicInteger();

        ScriptedConsumer(String id) {
            this.id = id;
        }

        void deliver(List<QueuedMessage> batch) {
            synchronized (batches) {
                batches.addLast(batch);
            }
        }

        /// The next [#poll] answers [PollResult.QueueMissing] instead of
        /// consulting the script (owner ruling 2026-09-11).
        void answerQueueMissingOnNextPoll() {
            answerQueueMissingNext = true;
        }

        /// Fails this poll and every poll after it.
        void failAlwaysWith(RuntimeException e) {
            failure = e;
            failForever = true;
        }

        /// Fails the next poll, then behaves.
        void failOnceWith(RuntimeException e) {
            failure = e;
            failForever = false;
        }

        void stop() {
            stopped.set(true);
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) {
            polls.incrementAndGet();
            if (stopped.get()) {
                return PollResult.STOPPED;
            }
            if (answerQueueMissingNext) {
                answerQueueMissingNext = false;
                return PollResult.QUEUE_MISSING;
            }
            var thrown = failure;
            if (thrown != null) {
                if (!failForever) {
                    failure = null;
                }
                throw thrown;
            }
            synchronized (batches) {
                var next = batches.pollFirst();
                return next == null ? PollResult.empty() : PollResult.of(next);
            }
        }

        @Override
        public boolean ack(QueuedMessage message) {
            return true;
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public boolean honoursDelayedReturn() {
            return true;
        }

        @Override
        public Optional<QueueMetrics> metrics() {
            return Optional.empty();
        }

        @Override
        public void close() {
            stopped.set(true);
        }
    }

    private static final class RecordingWarnings implements Warnings {
        final List<String> raised = new CopyOnWriteArrayList<>();

        @Override
        public void raise(Severity severity, String category, String message) {
            raised.add(severity + " " + category + " " + message);
        }
    }

    /// A consumer whose SECOND poll onward blocks until [#release] is
    /// called — the shape a continuous-subscription backend takes while
    /// genuinely idle (`NatsQueue`, `docs/spec/router.md` §3.2, §5 row 47)
    /// — while independently reporting a controllable
    /// [Consumer#lastBrokerActivity]. The FIRST poll returns empty
    /// immediately, purely to seed [ConsumerLoop#lastPoll] with a timestamp
    /// a test can then advance the clock past — the shape a poll that is
    /// blocked right now, on an already-stale prior heartbeat, actually
    /// takes.
    private static final class BlockingBrokerConsumer implements Consumer {
        private final String id;
        final AtomicInteger polls = new AtomicInteger();
        private final AtomicReference<Instant> brokerActivity = new AtomicReference<>();
        private final CountDownLatch releaseLatch = new CountDownLatch(1);

        BlockingBrokerConsumer(String id) {
            this.id = id;
        }

        void setBrokerActivity(Instant instant) {
            brokerActivity.set(instant);
        }

        void release() {
            releaseLatch.countDown();
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) throws InterruptedException {
            if (polls.incrementAndGet() == 1) {
                return PollResult.empty();
            }
            releaseLatch.await();
            return PollResult.empty();
        }

        @Override
        public Optional<Instant> lastBrokerActivity() {
            return Optional.ofNullable(brokerActivity.get());
        }

        @Override
        public boolean ack(QueuedMessage message) {
            return true;
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public boolean honoursDelayedReturn() {
            return true;
        }

        @Override
        public Optional<QueueMetrics> metrics() {
            return Optional.empty();
        }

        @Override
        public void close() {
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
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

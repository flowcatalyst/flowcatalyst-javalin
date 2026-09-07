package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

        assertThat(elapsed)
                .as("the loop must resume within 100 ms of capacity returning, not wait out a fixed pause")
                .isLessThan(Duration.ofMillis(100));
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

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(5));
        }
        throw new AssertionError("condition not met within 10s");
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
        final AtomicInteger polls = new AtomicInteger();

        ScriptedConsumer(String id) {
            this.id = id;
        }

        void deliver(List<QueuedMessage> batch) {
            synchronized (batches) {
                batches.addLast(batch);
            }
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
}

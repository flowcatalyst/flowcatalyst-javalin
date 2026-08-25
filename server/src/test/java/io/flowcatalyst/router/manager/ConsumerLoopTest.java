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
    private Thread loopThread;

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
        if (pool != null) {
            pool.close();
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

        sleep(Duration.ofMillis(2_500)); // more than one ALL_FULL_PAUSE

        assertThat(warnings.raised).hasSize(1);
    }

    @Test
    @DisplayName("a full batch is re-polled immediately; a partial batch pauses first")
    void fullBatchRepollsImmediately() {
        // A full batch says there is more work; a partial one says the queue
        // is draining and a brief pause lets it refill.
        consumer.deliver(batch(IntStream.range(0, ConsumerLoop.MAX_POLL)
                .mapToObj(i -> "m" + i).toArray(String[]::new)));
        consumer.deliver(batch("second-batch"));
        start(manager());

        long startedAt = System.nanoTime();
        await(() -> delivered.contains("second-batch"));
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("a full batch must not wait out the partial-batch pause")
                .isLessThan(ConsumerLoop.PARTIAL_BATCH_PAUSE);
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

    private static List<QueuedMessage> batch(String... ids) {
        return java.util.Arrays.stream(ids).map(ConsumerLoopTest::message).toList();
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

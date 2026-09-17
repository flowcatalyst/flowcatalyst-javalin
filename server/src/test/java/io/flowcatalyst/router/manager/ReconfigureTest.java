package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Mediator;
import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.ConsumerBuild;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/// Applying a new configuration to a running router
/// (`docs/spec/router.md` §8.2).
class ReconfigureTest {

    private final Clock clock = Clock.systemUTC();
    private final List<Pool> built = new CopyOnWriteArrayList<>();
    private final List<FakeConsumer> consumersBuilt = new CopyOnWriteArrayList<>();
    private final List<String> unbuildable = new CopyOnWriteArrayList<>();

    private final RouterManager manager = new RouterManager(
            new InFlightTracker(clock), Warnings.NO_OP, clock,
            config -> {
                var pool = new Pool(config, MEDIATOR, NO_OP_BROKER, PoolMetrics.NO_OP, Clock.systemUTC());
                built.add(pool);
                return pool;
            });

    private final RouterManager.ConsumerFactory consumerFactory = queue -> {
        if (unbuildable.contains(queue.queueName())) {
            return ConsumerBuild.FAILED;
        }
        var consumer = new FakeConsumer(queue.queueName());
        consumersBuilt.add(consumer);
        return ConsumerBuild.of(consumer);
    };

    @AfterEach
    void closePools() {
        built.forEach(Pool::close);
    }

    // ── Pools ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the default pool is always present, even when the config omits it")
    void defaultPoolIsAlwaysPresent() {
        // poolFor falls back to it; a config without it would leave messages
        // with nowhere to go.
        manager.reconfigure(new RouterConfig(List.of(pool("A", 4, 0)), List.of()), consumerFactory);

        assertThat(manager.pools()).containsKeys("A", RouterManager.DEFAULT_POOL);
    }

    @Test
    @DisplayName("a pool the config drops is stopped and forgotten")
    void droppedPoolIsStopped() {
        manager.reconfigure(new RouterConfig(List.of(pool("A", 4, 0), pool("B", 4, 0)), List.of()), consumerFactory);

        var result = manager.reconfigure(new RouterConfig(List.of(pool("A", 4, 0)), List.of()), consumerFactory);

        assertThat(manager.pools()).containsKey("A").doesNotContainKey("B");
        assertThat(result.poolsRemoved()).isOne();
    }

    @Test
    @DisplayName("an existing pool is adjusted in place, keeping its work")
    void existingPoolIsAdjustedNotRebuilt() {
        // A pool can be tuned hot, so its buffered work and in-flight
        // deliveries survive a config change. A consumer cannot — see below.
        manager.reconfigure(new RouterConfig(List.of(pool("A", 4, 60)), List.of()), consumerFactory);
        var first = manager.pools().get("A");

        manager.reconfigure(new RouterConfig(List.of(pool("A", 8, 120)), List.of()), consumerFactory);

        assertThat(manager.pools().get("A")).isSameAs(first);
        assertThat(built).hasSize(2); // A and DEFAULT-POOL, neither rebuilt
    }

    @Test
    @DisplayName("a zero concurrency leaves a running pool alone rather than shrinking it")
    void zeroConcurrencyDoesNotShrinkARunningPool() {
        manager.reconfigure(new RouterConfig(List.of(pool("A", 8, 0)), List.of()), consumerFactory);

        manager.reconfigure(new RouterConfig(List.of(pool("A", 0, 600)), List.of()), consumerFactory);

        // Still 8: a config that states no concurrency is not asking for none.
        // This is the case Pool.Config cannot even represent, which is why the
        // wire shape is a separate type.
        assertThat(manager.pools().get("A").config().concurrency()).isEqualTo(8);
    }

    @Test
    @DisplayName("a new pool with no concurrency derives one from its rate limit")
    void newPoolDerivesConcurrencyFromRate() {
        // 60 requests a minute has no use for 20 workers; they would queue on
        // the limiter. At least one, so a pool always makes progress.
        manager.reconfigure(new RouterConfig(
                List.of(pool("slow", 0, 60), pool("tiny", 0, 6), pool("none", 0, 0)), List.of()), consumerFactory);

        assertThat(manager.pools().get("slow").config().concurrency()).isOne();
        assertThat(manager.pools().get("tiny").config().concurrency()).isOne();
        assertThat(manager.pools().get("none").config().concurrency()).isOne();
    }

    @Test
    @DisplayName("a synthesised per-client fallback pool survives a reconfigure that never mentions it")
    void synthesisedPoolSurvivesReconfigure() {
        // It is never in the config by construction, so a reconfigure that
        // removed unmentioned pools would delete it moments after creating it.
        manager.reconfigure(new RouterConfig(List.of(pool("A", 4, 0)), List.of()), consumerFactory);
        manager.poolFor(io.flowcatalyst.router.pool.QueuedMessage.of(
                new io.flowcatalyst.router.wire.Message("m1", "acme-DEFAULT-POOL", null, null,
                        io.flowcatalyst.router.wire.MediationType.HTTP, "https://x.test/h", null, false,
                        DispatchMode.IMMEDIATE),
                "b1", "r1", "q1"));
        assertThat(manager.pools()).containsKey("acme-DEFAULT-POOL");

        manager.reconfigure(new RouterConfig(List.of(pool("A", 4, 0)), List.of()), consumerFactory);

        assertThat(manager.pools()).containsKey("acme-DEFAULT-POOL");
    }

    @Test
    @DisplayName("X-11: a removed pool drains its buffered ordered group in the background, without blocking the reconfigure")
    void removedPoolDrainsInBackground() throws InterruptedException {
        var release = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<String>();
        var recordingBroker = new RecordingBroker();
        Mediator slow = (message, recordFailure) -> {
            release.await();
            delivered.add(message.id());
            return MediationOutcome.Success.of(200);
        };
        var localBuilt = new CopyOnWriteArrayList<Pool>();
        var localManager = new RouterManager(new InFlightTracker(clock), Warnings.NO_OP, clock, cfg -> {
            var pool = new Pool(cfg, slow, recordingBroker, PoolMetrics.NO_OP, Clock.systemUTC());
            localBuilt.add(pool);
            return pool;
        });
        try {
            localManager.reconfigure(new RouterConfig(List.of(pool("A", 2, 0)), List.of()), consumerFactory);
            var poolA = localManager.pools().get("A");
            poolA.submit(ordered("g1", "m0"));
            poolA.submit(ordered("g1", "m1"));
            poolA.submit(ordered("g1", "m2"));
            // Let the head grab the latch before removing the pool, so there
            // is genuinely a buffer still draining when it is removed.
            awaitTrue(() -> poolA.activeWorkers() == 1);

            long startedAt = System.nanoTime();
            localManager.reconfigure(new RouterConfig(List.of(), List.of()), consumerFactory);
            var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            // Pool.close()'s own await-termination budget is 5s+2s=7s; a
            // reconfigure that (pre-fix) called close() synchronously on a
            // pool still holding a blocked worker would take that long. Well
            // under it proves the removal did not wait for the drain.
            assertThat(elapsed).as("reconfigure must not block on the pool draining")
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(localManager.pools()).as("gone from routing at once").doesNotContainKey("A");
            assertThat(localManager.allPools()).as("but still visible while draining").containsKey("A");

            // A message submitted to the drained pool object directly (as
            // the group's own worker would if it looped back) is nacked, not
            // silently accepted.
            poolA.submit(ordered("g1", "m-late"));
            assertThat(recordingBroker.nacked).contains("m-late");

            release.countDown();
            awaitTrue(() -> delivered.size() == 3);
            assertThat(delivered).as("the buffer kept draining after removal").containsExactly("m0", "m1", "m2");
            awaitTrue(poolA::drained);

            assertThat(localManager.closeDrainedPools()).as("housekeeping closes it once drained").isOne();
            assertThat(localManager.allPools()).as("gone once closed").doesNotContainKey("A");
        } finally {
            release.countDown();
            localBuilt.forEach(Pool::close);
        }
    }

    // ── Consumers ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a new queue gets a consumer")
    void newQueueIsStarted() {
        var result = manager.reconfigure(
                new RouterConfig(List.of(), List.of(QueueConfig.of("q://1"))), consumerFactory);

        assertThat(result.consumersStarted()).isOne();
        assertThat(manager.consumer("q://1")).isPresent();
    }

    @Test
    @DisplayName("an unchanged queue keeps its consumer, so its deliveries are not aborted")
    void unchangedQueueKeepsItsConsumer() {
        var config = new RouterConfig(List.of(), List.of(QueueConfig.of("q://1")));
        manager.reconfigure(config, consumerFactory);
        var first = manager.consumer("q://1").orElseThrow();

        var result = manager.reconfigure(config, consumerFactory);

        assertThat(manager.consumer("q://1")).containsSame(first);
        assertThat(result.consumersStarted()).isZero();
        assertThat(result.consumersStopped()).isZero();
    }

    @Test
    @DisplayName("any change to a queue rebuilds its consumer, but the OLD one lingers rather than aborting (R-26)")
    void changedQueueIsRebuilt() {
        // A consumer's identity is bound to a broker connection, so it cannot
        // be tuned in place the way a pool can. The old one is DETACHED, not
        // closed: an in-flight delivery or a buffered message still
        // referencing it must be able to ack/nack cleanly instead of finding
        // it torn down (`docs/spec/router-completion.md` §2 ruling 5).
        manager.reconfigure(new RouterConfig(List.of(),
                List.of(new QueueConfig("q://1", "orders", 1, 30))), consumerFactory);
        var first = (FakeConsumer) manager.consumer("orders").orElseThrow();

        var result = manager.reconfigure(new RouterConfig(List.of(),
                List.of(new QueueConfig("q://1", "orders", 4, 30))), consumerFactory);

        assertThat(first.closed).as("not closed synchronously with the reconfigure").isFalse();
        assertThat(manager.activeConsumer("orders").orElseThrow())
                .as("the NEW consumer is what actually polls").isNotSameAs(first);
        assertThat(result.consumersStopped()).isOne();
        assertThat(result.consumersStarted()).isOne();
        assertThat(result.replacedQueues()).as("a change, not a removal").containsExactly("orders");

        // Nothing in the tracker references the old consumer's queue, so
        // housekeeping retires it.
        manager.retireLingeringConsumers();
        assertThat(first.closed).as("closed once nothing references it any more").isTrue();
    }

    @Test
    @DisplayName("a queue the config drops lingers, resolvable for ack, until the tracker clears and housekeeping retires it")
    void droppedQueueIsClosed() {
        manager.reconfigure(new RouterConfig(List.of(), List.of(QueueConfig.of("q://1"))), consumerFactory);
        var consumer = (FakeConsumer) manager.consumer("q://1").orElseThrow();

        var result = manager.reconfigure(RouterConfig.EMPTY, consumerFactory);

        assertThat(consumer.closed).as("not closed synchronously — X-11/R-26").isFalse();
        assertThat(manager.consumer("q://1")).as("still resolvable while lingering").isPresent();
        assertThat(manager.activeConsumer("q://1")).as("but no longer being polled").isEmpty();
        assertThat(result.replacedQueues()).as("a removal, not a change").isEmpty();

        manager.retireLingeringConsumers();

        assertThat(consumer.closed).isTrue();
        assertThat(manager.consumer("q://1")).isEmpty();
    }

    @Test
    @DisplayName("a queue that cannot be built does not stop the others starting")
    void oneBadQueueDoesNotHalveTheRouter() {
        // Deliberate deviation: Go aborts the reconfigure mid-way, leaving
        // earlier changes applied and later queues unstarted (Q35). A single
        // bad URI would silently halve the router.
        unbuildable.add("broken");

        var result = manager.reconfigure(new RouterConfig(List.of(), List.of(
                QueueConfig.of("q://1"),
                new QueueConfig("q://broken", "broken", 1, 30),
                QueueConfig.of("q://3"))), consumerFactory);

        assertThat(result.consumersStarted()).isEqualTo(2);
        assertThat(result.failedQueues()).containsExactly("broken");
        assertThat(result.complete()).isFalse();
        assertThat(manager.consumer("q://3")).as("a later queue still starts").isPresent();
    }

    @Test
    @DisplayName("owner ruling 2026-09-11: a queue that does not exist yet is NOT a failed queue")
    void missingQueueIsNotReportedAsFailed() {
        // Mutant: fold ConsumerBuild.Missing into the Failed branch of
        // RouterManager#applyConsumers → this test fails, because the queue
        // would show up in failedQueues() exactly as a genuine build failure
        // does, and RouterServer#apply would raise the CONFIGURATION ERROR
        // warning "running without 1 configured queue(s)" for it.
        RouterManager.ConsumerFactory missingFactory = queue -> ConsumerBuild.MISSING;

        var result = manager.reconfigure(
                new RouterConfig(List.of(), List.of(QueueConfig.of("q://missing"))), missingFactory);

        assertThat(result.failedQueues()).as("missing is a third outcome, not a failure").isEmpty();
        assertThat(result.consumersStarted()).isZero();
        assertThat(result.complete()).as("a missing queue must not make the reconfigure incomplete").isTrue();
        assertThat(manager.consumer("q://missing")).isEmpty();
    }

    @Test
    @DisplayName("a fully applied reconfigure reports itself complete")
    void completeReconfigure() {
        var result = manager.reconfigure(
                new RouterConfig(List.of(pool("A", 4, 0)), List.of(QueueConfig.of("q://1"))), consumerFactory);

        assertThat(result.complete()).isTrue();
        assertThat(result.failedQueues()).isEmpty();
        assertThat(result.pools()).isEqualTo(2); // A plus DEFAULT-POOL
    }

    /// A pool exactly as a configuration document would state it — zero
    /// concurrency included, which is the case the runtime type cannot hold.
    private static PoolSpec pool(String code, int concurrency, int rpm) {
        return new PoolSpec(code, concurrency, rpm);
    }

    private static QueuedMessage ordered(String group, String id) {
        return QueuedMessage.of(
                new io.flowcatalyst.router.wire.Message(id, "", null, null,
                        io.flowcatalyst.router.wire.MediationType.HTTP, "https://x.test/h", group, false,
                        DispatchMode.NEXT_ON_ERROR),
                "broker-" + id, "receipt-" + id, "queue-1");
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("condition not met within 10s");
    }

    private static final class RecordingBroker implements Broker {
        final List<String> acked = new CopyOnWriteArrayList<>();
        final List<String> nacked = new CopyOnWriteArrayList<>();

        @Override
        public void ack(QueuedMessage message) {
            acked.add(message.id());
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
            nacked.add(message.id());
        }

        @Override
        public void release(QueuedMessage message) {
        }

        @Override
        public boolean honoursDelayedReturn(QueuedMessage message) {
            return true;
        }
    }

    private static final Mediator MEDIATOR = (message, recordFailure) -> MediationOutcome.Success.of(200);

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

    private static final class FakeConsumer implements Consumer {
        private final String id;
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
            closed = true;
        }
    }
}

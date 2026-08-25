package io.flowcatalyst.router.manager;

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
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.MediationOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

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
            return Optional.empty();
        }
        var consumer = new FakeConsumer(queue.queueName());
        consumersBuilt.add(consumer);
        return Optional.of(consumer);
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
                        io.flowcatalyst.router.wire.DispatchMode.IMMEDIATE),
                "b1", "r1", "q1"));
        assertThat(manager.pools()).containsKey("acme-DEFAULT-POOL");

        manager.reconfigure(new RouterConfig(List.of(pool("A", 4, 0)), List.of()), consumerFactory);

        assertThat(manager.pools()).containsKey("acme-DEFAULT-POOL");
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
    @DisplayName("any change to a queue rebuilds its consumer")
    void changedQueueIsRebuilt() {
        // A consumer's identity is bound to a broker connection, so it cannot
        // be tuned in place the way a pool can. Rebuilding aborts that
        // queue's in-flight deliveries and parks its ordered groups until
        // redelivery resumes them — which is why a queue edit is not cheap.
        manager.reconfigure(new RouterConfig(List.of(),
                List.of(new QueueConfig("q://1", "orders", 1, 30))), consumerFactory);
        var first = (FakeConsumer) manager.consumer("orders").orElseThrow();

        var result = manager.reconfigure(new RouterConfig(List.of(),
                List.of(new QueueConfig("q://1", "orders", 4, 30))), consumerFactory);

        assertThat(first.closed).as("the old consumer is closed, not leaked").isTrue();
        assertThat(manager.consumer("orders").orElseThrow()).isNotSameAs(first);
        assertThat(result.consumersStopped()).isOne();
        assertThat(result.consumersStarted()).isOne();
    }

    @Test
    @DisplayName("a queue the config drops is closed and forgotten")
    void droppedQueueIsClosed() {
        manager.reconfigure(new RouterConfig(List.of(), List.of(QueueConfig.of("q://1"))), consumerFactory);
        var consumer = (FakeConsumer) manager.consumer("q://1").orElseThrow();

        manager.reconfigure(RouterConfig.EMPTY, consumerFactory);

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
        public Optional<QueueMetrics> metrics() {
            return Optional.empty();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

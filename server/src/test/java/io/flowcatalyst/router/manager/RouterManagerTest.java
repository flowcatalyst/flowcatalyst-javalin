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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Routing and pool resolution (`docs/spec/router.md` §3.3).
class RouterManagerTest {

    private final Clock clock = Clock.systemUTC();
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final RecordingWarnings warnings = new RecordingWarnings();
    private final List<RecordingPool> created = new ArrayList<>();
    private final RecordingConsumer source = new RecordingConsumer("queue-1");

    private final RouterManager manager =
            new RouterManager(tracker, warnings, clock, config -> {
                var recording = RecordingPool.of(config.code());
                created.add(recording);
                return recording.pool();
            });

    @AfterEach
    void closePools() {
        created.forEach(recording -> recording.pool().close());
    }

    // ── Ownership ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a new message is submitted to its pool")
    void newMessageIsSubmitted() {
        var pool = registerPool(RouterManager.DEFAULT_POOL);
        manager.registerConsumer(source);

        manager.route(List.of(message("m1", "b1", "")), source);

        await(() -> pool.delivered().contains("m1"));
        assertThat(source.acked).isEmpty();
    }

    @Test
    @DisplayName("a redelivery is dropped, not acked — the owner is still working on it")
    void redeliveryIsDropped() {
        var pool = registerPool(RouterManager.DEFAULT_POOL);
        manager.registerConsumer(source);
        manager.route(List.of(message("m1", "b1", "")), source);
        await(() -> pool.delivered().contains("m1"));

        manager.route(List.of(message("m1", "b1", "")), source);

        // Acking here would delete the delivery out from under the owner.
        assertThat(source.acked).isEmpty();
        assertThat(pool.delivered()).containsExactly("m1");
    }

    @Test
    @DisplayName("an external requeue is acked on its own handle and never delivered")
    void externalRequeueIsAckedAway() {
        // A second, DISTINCT broker delivery of a message we already own.
        var pool = registerPool(RouterManager.DEFAULT_POOL);
        manager.registerConsumer(source);
        manager.route(List.of(message("m1", "b1", "")), source);
        await(() -> pool.delivered().contains("m1"));

        manager.route(List.of(message("m1", "b2", "")), source);

        assertThat(source.acked).containsExactly("m1");
        assertThat(pool.delivered()).containsExactly("m1");
    }

    // ── Pool resolution ─────────────────────────────────────────────────

    @Test
    @DisplayName("a known pool code routes to that pool")
    void knownPoolCode() {
        registerPool(RouterManager.DEFAULT_POOL);
        var fast = registerPool("acme-FAST");

        manager.route(List.of(message("m1", "b1", "acme-FAST")), source);

        await(() -> fast.delivered().contains("m1"));
        assertThat(warnings.raised).isEmpty();
    }

    @Test
    @DisplayName("an empty pool code goes to the default pool without a warning")
    void emptyPoolCodeIsNotAMistake() {
        var fallback = registerPool(RouterManager.DEFAULT_POOL);

        manager.route(List.of(message("m1", "b1", "")), source);

        await(() -> fallback.delivered().contains("m1"));
        assertThat(warnings.raised).as("naming no pool is not an error").isEmpty();
    }

    @Test
    @DisplayName("an unknown pool code falls back to the default pool and warns")
    void unknownPoolCodeWarns() {
        var fallback = registerPool(RouterManager.DEFAULT_POOL);

        manager.route(List.of(message("m1", "b1", "nonexistent")), source);

        await(() -> fallback.delivered().contains("m1"));
        assertThat(warnings.raised).singleElement().asString()
                .contains("ROUTING").contains("nonexistent");
    }

    @Test
    @DisplayName("a per-client fallback pool is synthesised rather than treated as unknown")
    void perClientFallbackPoolIsSynthesised() {
        // The router's config comes from an external service that does not
        // know about {client}-DEFAULT-POOL codes. Treating them as unknown
        // would send every client's unpooled traffic to one shared pool and
        // warn once per message while doing it.
        registerPool(RouterManager.DEFAULT_POOL);

        manager.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL")), source);

        assertThat(created).hasSize(1);
        await(() -> created.getFirst().delivered().contains("m1"));
        assertThat(warnings.raised).isEmpty();
        assertThat(manager.pools()).containsKey("acme-DEFAULT-POOL");
    }

    @Test
    @DisplayName("a synthesised fallback pool is reused, not rebuilt per message")
    void synthesisedPoolIsReused() {
        registerPool(RouterManager.DEFAULT_POOL);

        manager.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL"),
                message("m2", "b2", "acme-DEFAULT-POOL")), source);

        assertThat(created).hasSize(1);
    }

    @Test
    @DisplayName("different clients get different fallback pools")
    void perClientPoolsAreDistinct() {
        // The whole point of the namespacing ruling: acme and globex must not
        // share concurrency just because neither named a pool.
        registerPool(RouterManager.DEFAULT_POOL);

        manager.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL"),
                message("m2", "b2", "globex-DEFAULT-POOL")), source);

        assertThat(created).hasSize(2);
        assertThat(manager.pools()).containsKeys("acme-DEFAULT-POOL", "globex-DEFAULT-POOL");
    }

    @Test
    @DisplayName("with no pool at all the message is handed back, not held")
    void noPoolNacks() {
        // Before the first reconfigure, or after shutdown.
        manager.registerConsumer(source);

        manager.route(List.of(message("m1", "b1", "")), source);

        assertThat(source.nacked).containsEntry("m1", RouterManager.NO_POOL_NACK_DELAY);
        // Ownership released too, or a later redelivery could never be taken up.
        assertThat(tracker.size()).isZero();
    }

    // ── Backpressure ────────────────────────────────────────────────────

    @Test
    @DisplayName("capacity is reported across all pools, so one full pool does not pause the router")
    void capacityAcrossPools() {
        var busy = registerPool("busy");
        registerPool("idle");

        assertThat(manager.anyPoolHasCapacity()).isTrue();
        assertThat(busy.queueSize()).isZero();
    }

    @Test
    @DisplayName("with no pools at all there is no capacity")
    void noPoolsMeansNoCapacity() {
        assertThat(manager.anyPoolHasCapacity()).isFalse();
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private RecordingPool registerPool(String code) {
        var recording = RecordingPool.of(code);
        manager.registerPool(code, recording.pool());
        return recording;
    }

    private static QueuedMessage message(String id, String brokerId, String poolCode) {
        return QueuedMessage.of(
                new Message(id, poolCode, null, null, MediationType.HTTP, "https://x.test/h",
                        null, false, DispatchMode.IMMEDIATE),
                brokerId, "receipt-" + brokerId, "queue-1");
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(Duration.ofMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("condition not met");
    }

    /// A real [Pool] alongside a record of what actually reached its
    /// mediator — the pool is final, and rightly so, so this composes rather
    /// than subclasses.
    private record RecordingPool(Pool pool, List<String> delivered) {

        static RecordingPool of(String code) {
            var delivered = new CopyOnWriteArrayList<String>();
            Mediator mediator = (message, recordFailure) -> {
                delivered.add(message.id());
                return MediationOutcome.Success.of(200);
            };
            Broker noOp = new Broker() {
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
            return new RecordingPool(
                    new Pool(new Pool.Config(code, 4, 0), mediator, noOp, PoolMetrics.NO_OP, Clock.systemUTC()),
                    delivered);
        }

        int queueSize() {
            return pool.queueSize();
        }
    }

    private static final class RecordingWarnings implements Warnings {
        final List<String> raised = new CopyOnWriteArrayList<>();

        @Override
        public void raise(Severity severity, String category, String message) {
            raised.add(severity + " " + category + " " + message);
        }
    }

    private static final class RecordingConsumer implements Consumer {
        private final String id;
        final List<String> acked = new CopyOnWriteArrayList<>();
        final Map<String, Duration> nacked = new ConcurrentHashMap<>();

        RecordingConsumer(String id) {
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
        public void nack(QueuedMessage message, Duration delay) {
            nacked.put(message.id(), delay);
        }

        @Override
        public Optional<QueueMetrics> metrics() {
            return Optional.empty();
        }

        @Override
        public void close() {
        }
    }
}

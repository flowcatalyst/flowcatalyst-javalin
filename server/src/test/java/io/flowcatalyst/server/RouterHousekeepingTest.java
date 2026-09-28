package io.flowcatalyst.server;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.lifecycle.LifecycleLoops;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.wire.MediationOutcome;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// The parked-group sweep is only a backstop if `Router.java` actually runs
/// it (review 2026-09-28). `NoOrphansTest` checks types, not wiring, and
/// `LifecycleLoops` has shipped unwired once before — so this builds the real
/// router and runs the real task it registered.
class RouterHousekeepingTest {

    private Router router;
    private Pool pool;

    @AfterEach
    void close() {
        if (pool != null) pool.close();
        if (router != null) router.close();
    }

    @Test
    @DisplayName("Router.build registers parked-group-release at its interval, and running it hands a parked group back")
    void parkedGroupReleaseIsRegisteredAndWired() {
        router = Router.build(Env.load(Map.of(
                "FC_ROUTER_ENABLED", "true",
                "FC_PLATFORM_ENABLED", "false",
                "FLOWCATALYST_DEV_MODE", "true")), null, Clock.systemUTC());

        var task = router.housekeepingTasks().stream()
                .filter(t -> t.name().equals(Router.PARKED_GROUP_RELEASE_TASK))
                .findFirst();
        assertThat(task).as("mutant: the task is not registered").isPresent();
        assertThat(task.get().interval()).as("mutant: registered at some other cadence")
                .isEqualTo(RouterManager.PARKED_GROUP_RELEASE_INTERVAL)
                .isEqualTo(Duration.ofSeconds(60));

        // A pool of the router's own manager holding a parked group: its drainer
        // failed and so did the hand-back, so the group waits for the sweep.
        var now = new AtomicReference<>(Instant.parse("2026-09-28T10:00:00Z"));
        var broker = new ParkingBroker();
        pool = new Pool(new Pool.Config("PARK-POOL", 1, 0), (message, recordFailure) -> MediationOutcome.Success.of(200),
                broker, PoolMetrics.NO_OP, fixedClock(now));
        router.manager().registerPool("PARK-POOL", pool);
        for (int i = 0; i < 3; i++) {
            pool.submit(message("m" + i));
        }
        broker.submitted.countDown();
        awaitParked(pool, 3);

        now.set(now.get().plus(RouterManager.DEFAULT_PARKED_GROUP_MAX_AGE).plusSeconds(1));
        task.get().action().run();

        assertThat(broker.nacked).as("mutant: the task runs something other than the sweep")
                .containsExactly("m0", "m1", "m2");
        assertThat(pool.queueSize()).isZero();
    }

    private static void awaitParked(Pool pool, int size) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.queueSize() == size && pool.groupSnapshot().stream().noneMatch(Pool.GroupSnapshot::draining)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the group never parked");
    }

    private static QueuedMessage message(String id) {
        return QueuedMessage.of(new Message(id, "", null, null, MediationType.HTTP, "https://x.test/h", "g", false,
                DispatchMode.BLOCK_ON_ERROR), "b-" + id, "r-" + id, "q://1");
    }

    private static Clock fixedClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    /// Breaks its contract exactly twice — the first ownership check (so the
    /// drainer throws) and the first nack (so the hand-back fails and the
    /// group parks) — then behaves.
    private static final class ParkingBroker implements Broker {
        final List<String> nacked = new CopyOnWriteArrayList<>();
        /// Holds the first drainer until every message is buffered behind it.
        final java.util.concurrent.CountDownLatch submitted = new java.util.concurrent.CountDownLatch(1);
        private final AtomicInteger ownsCalls = new AtomicInteger();
        private final AtomicInteger nackCalls = new AtomicInteger();

        @Override
        public boolean owns(QueuedMessage message) {
            if (ownsCalls.getAndIncrement() == 0) {
                try {
                    submitted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("broker broke its contract");
            }
            return true;
        }

        @Override
        public void ack(QueuedMessage message) {
        }

        @Override
        public void defer(QueuedMessage message, Duration delay) {
            nack(message, delay);
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
            if (nackCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("broker broke its contract again");
            }
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
}

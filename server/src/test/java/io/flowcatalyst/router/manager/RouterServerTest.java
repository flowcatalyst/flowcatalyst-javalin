package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Mediator;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.standby.LeaderElection;
import io.flowcatalyst.router.standby.LockStore;
import io.flowcatalyst.router.wire.MediationOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Booting and failing over the router (`docs/spec/router.md` §4.5, §4.7).
class RouterServerTest {

    private final Clock clock = Clock.systemUTC();
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final RecordingWarnings warnings = new RecordingWarnings();
    private final List<FakeConsumer> built = new CopyOnWriteArrayList<>();
    private final List<String> unbuildable = new CopyOnWriteArrayList<>();
    private final AtomicInteger buildDelayMillis = new AtomicInteger();
    private final FakeStore store = new FakeStore();
    private final List<Pool> pools = new CopyOnWriteArrayList<>();
    private RouterServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        pools.forEach(Pool::close);
    }

    private final List<String> nacked = new CopyOnWriteArrayList<>();

    private RouterManager manager() {
        Mediator mediator = (message, recordFailure) -> MediationOutcome.Success.of(200);
        Broker recording = new Broker() {
            @Override
            public void ack(QueuedMessage message) {
            }

            @Override
            public void nack(QueuedMessage message, Duration delay) {
                nacked.add(message.id());
            }

        @Override
        public void release(QueuedMessage message) {
        }
        };
        return new RouterManager(tracker, warnings, clock, config -> {
            var pool = new Pool(config, mediator, recording, PoolMetrics.NO_OP, clock);
            pools.add(pool);
            return pool;
        });
    }

    private static QueuedMessage message(String id) {
        return message(id, "");
    }

    private static QueuedMessage message(String id, String poolCode) {
        return QueuedMessage.of(
                new io.flowcatalyst.router.wire.Message(id, poolCode, null, null,
                        io.flowcatalyst.router.wire.MediationType.HTTP, "https://x.test/h", null, false,
                        io.flowcatalyst.router.wire.DispatchMode.IMMEDIATE),
                "b-" + id, "r-" + id, "q://1");
    }

    private RouterServer server(LeaderElection.Config electionConfig, RouterConfig config) {
        var manager = manager();
        election = new LeaderElection(electionConfig, store, clock);
        server = new RouterServer(manager, tracker, election, this::build,
                RouterServer.ConfigSource.fixed(config), warnings, clock, Duration.ofSeconds(1));
        return server;
    }

    private LeaderElection election;

    private Optional<Consumer> build(QueueConfig queue) {
        if (unbuildable.contains(queue.queueName())) {
            return Optional.empty();
        }
        int delay = buildDelayMillis.get();
        if (delay > 0) {
            try {
                Thread.sleep(Duration.ofMillis(delay));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        var consumer = new FakeConsumer(queue.queueName());
        built.add(consumer);
        return Optional.of(consumer);
    }

    @Test
    @DisplayName("a single-instance router starts consuming immediately")
    void singleInstanceStartsConsuming() {
        var router = server(LeaderElection.Config.disabled(), config("q://1", "q://2"));

        router.start();

        assertThat(router.leader()).isTrue();
        assertThat(router.running()).isTrue();
        await(() -> router.activeLoops() == 2);
    }

    @Test
    @DisplayName("a listener registered after start would miss the initial leadership")
    void listenerOrderingIsLoadBearing() {
        // RouterServer registers before start() precisely so an instance that
        // comes up as leader is caught by the ordinary transition. Registered
        // afterwards, the router would idle until the next heartbeat — which
        // is a boot that silently does nothing for ten seconds.
        var election = new LeaderElection(LeaderElection.Config.disabled(), store, clock);
        var seen = new CopyOnWriteArrayList<Boolean>();

        election.start();
        election.onChange(change -> seen.add(change.leader()));

        assertThat(election.isLeader()).isTrue();
        assertThat(seen).as("the transition already happened").isEmpty();
    }

    @Test
    @DisplayName("a follower builds nothing and polls nothing")
    void followerConsumesNothing() {
        // Leadership gates everything that touches a queue: a follower that
        // polled would deliver the same messages as the leader.
        store.holder = "someone-else";

        var router = server(LeaderElection.Config.of("fc:leader"), config("q://1"));
        router.start();

        assertThat(router.leader()).isFalse();
        assertThat(router.running()).isFalse();
        assertThat(built).isEmpty();
        assertThat(router.activeLoops()).isZero();
    }

    @Test
    @DisplayName("gaining leadership starts the consumers")
    void gainingLeadershipStartsConsumers() {
        store.holder = "someone-else";
        var router = server(LeaderElection.Config.of("fc:leader"), config("q://1", "q://2"));
        router.start();
        assertThat(built).isEmpty();

        store.holder = null; // the previous leader died
        election.contendNow();

        await(() -> router.activeLoops() == 2);
        assertThat(built).hasSize(2);
    }

    @Test
    @DisplayName("losing leadership stops every loop and hands the work back")
    void losingLeadershipStopsEverything() {
        var router = server(LeaderElection.Config.of("fc:leader"), config("q://1", "q://2"));
        router.start();
        await(() -> router.activeLoops() == 2);

        store.holder = "someone-else";
        election.contendNow();

        assertThat(router.running()).isFalse();
        assertThat(router.activeLoops()).isZero();
        assertThat(built).allSatisfy(consumer -> assertThat(consumer.closed).isTrue());
    }

    @Test
    @DisplayName("a pool still delivers after a failover and back")
    void poolStillWorksAfterFailoverAndBack() {
        // The test that matters, and the one the earlier version was missing:
        // asserting the pool OBJECTS survive says nothing about whether they
        // still work. Stopping a pool is permanent — `stopped` is never
        // reset — so a stand-down that stopped its pools would regain
        // leadership and quietly nack every message for ever.
        var router = server(LeaderElection.Config.of("fc:leader"), config("q://1"));
        router.start();
        await(() -> router.activeLoops() == 1);
        var pool = manager().pools().get(RouterManager.DEFAULT_POOL);

        store.holder = "someone-else";
        election.contendNow();          // lose it
        store.holder = null;
        election.contendNow();          // and get it back

        await(() -> router.activeLoops() == 1);
        var survivor = pools.getFirst();
        survivor.submit(message("after-failover"));
        await(() -> survivor.queueSize() == 0);
        assertThat(nacked).as("a surviving pool must deliver, not hand back").doesNotContain("after-failover");
    }

    @Test
    @DisplayName("the pools survive a failover, so regaining leadership does not rebuild the world")
    void poolsSurviveFailover() {
        // Only the sources start and stop. Rebuilding pools, the tracker and
        // the metrics on every transition would make a failover far more
        // disruptive than it needs to be.
        var router = server(LeaderElection.Config.of("fc:leader"), config("q://1"));
        router.start();
        await(() -> router.activeLoops() == 1);
        int poolsAfterStart = pools.size();

        store.holder = "someone-else";
        election.contendNow();

        assertThat(pools).hasSize(poolsAfterStart);
    }

    @Test
    @DisplayName("consumers are built concurrently, so one slow broker does not delay the rest")
    void consumersAreBuiltConcurrently() {
        // Built in turn, eight queues at 300ms each is 2.4 seconds and a
        // deployment waits for the sum rather than the maximum.
        buildDelayMillis.set(300);
        var queues = IntStream.range(0, 8).mapToObj(i -> "q://" + i).toArray(String[]::new);
        var router = server(LeaderElection.Config.disabled(), config(queues));

        long startedAt = System.nanoTime();
        router.start();
        await(() -> router.activeLoops() == 8);
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    @DisplayName("a queue that cannot be built is surfaced, not just logged")
    void unbuildableQueueRaisesAWarning() {
        // Running with fewer queues than configured means some are simply not
        // being consumed — an operator-visible condition, not a log line.
        unbuildable.add("broken");
        var router = server(LeaderElection.Config.disabled(),
                new RouterConfig(List.of(new PoolSpec("A", 2, 0)),
                        List.of(QueueConfig.of("q://1"), new QueueConfig("q://x", "broken", 1, 30))));

        router.start();

        await(() -> router.activeLoops() == 1);
        assertThat(warnings.raised).anySatisfy(raised ->
                assertThat(raised).contains("CONFIGURATION").contains("broken"));
    }

    @Test
    @DisplayName("A-10: a second applyConfiguration() call raises a live pool's concurrency, not just its config record")
    void secondApplyConfigurationAdjustsLivePoolConcurrency() throws InterruptedException {
        // The config-poll task (Router.java, CONFIG_POLL_INTERVAL) exists to
        // reach a pool that is already running: this proves a repeat call
        // actually moves the running pool's admitted concurrency, not merely
        // that RouterManager#reconfigure was invoked again.
        var configRef = new AtomicReference<>(
                new RouterConfig(List.of(new PoolSpec("A", 2, 0)), List.of(QueueConfig.of("q://1"))));
        var release = new CountDownLatch(1);
        Mediator blockingMediator = (message, recordFailure) -> {
            release.await();
            return MediationOutcome.Success.of(200);
        };
        var localPools = new CopyOnWriteArrayList<Pool>();
        var localManager = new RouterManager(tracker, warnings, clock, cfg -> {
            var pool = new Pool(cfg, blockingMediator, NO_OP_BROKER, PoolMetrics.NO_OP, clock);
            localPools.add(pool);
            return pool;
        });
        election = new LeaderElection(LeaderElection.Config.disabled(), store, clock);
        server = new RouterServer(localManager, tracker, election, this::build,
                () -> Optional.of(configRef.get()), warnings, clock, Duration.ofSeconds(1));

        server.start();
        await(() -> server.activeLoops() == 1);
        var pool = localManager.pools().get("A");

        for (int i = 0; i < 5; i++) {
            pool.submit(message("m" + i));
        }
        await(() -> pool.activeWorkers() == 2);
        assertThat(pool.activeWorkers()).as("the original concurrency caps active workers at 2").isEqualTo(2);

        configRef.set(new RouterConfig(List.of(new PoolSpec("A", 5, 0)), List.of(QueueConfig.of("q://1"))));
        server.applyConfiguration();

        await(() -> pool.activeWorkers() == 5);

        release.countDown();
    }

    @Test
    @DisplayName("E: a consumer paused for capacity beyond the stall threshold is not reported stalled")
    void capacityPausedConsumerIsNotStalled() throws InterruptedException {
        // Same shape as RouterApiTest's "a consumer that keeps polling stays
        // ready" (readinessStaysReadyForAPollingConsumer): jump the clock
        // past the stall threshold, then let the loop's own real-time ticks
        // refresh its heartbeat under the now-advanced clock. Here the tick
        // that refreshes it is a capacity pause, not a poll — the loop
        // deliberately stops polling once its only fed pool is full, and
        // stalledConsumers() must not read that silence as stalled.
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolatedTracker = new InFlightTracker(mutableClock);
        var release = new CountDownLatch(1);
        Mediator blockingMediator = (message, recordFailure) -> {
            release.await();
            return MediationOutcome.Success.of(200);
        };
        var localPools = new CopyOnWriteArrayList<Pool>();
        var localManager = new RouterManager(isolatedTracker, warnings, mutableClock, cfg -> {
            var pool = new Pool(cfg, blockingMediator, NO_OP_BROKER, PoolMetrics.NO_OP, mutableClock);
            localPools.add(pool);
            return pool;
        });
        var oneShot = new OneShotThenEmptyConsumer("q://cap");
        election = new LeaderElection(LeaderElection.Config.disabled(), store, mutableClock);
        var localServer = new RouterServer(localManager, isolatedTracker, election, q -> Optional.of(oneShot),
                RouterServer.ConfigSource.fixed(new RouterConfig(List.of(new PoolSpec("A", 1, 0)),
                        List.of(QueueConfig.of("q://cap")))),
                warnings, mutableClock, Duration.ofSeconds(1));
        try {
            localServer.start();
            await(() -> localServer.activeLoops() == 1);
            var pool = localManager.pools().get("A");
            await(() -> oneShot.delivered.get());

            // Fill pool "A" — the only pool this consumer's seed batch fed —
            // so every later poll is capacity-paused rather than empty.
            var filler = Thread.ofVirtual().start(() -> {
                int n = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    if (localManager.poolsHaveCapacity(java.util.Set.of("A"))) {
                        pool.submit(message("filler-" + n++, "A"));
                    } else {
                        Thread.onSpinWait();
                    }
                }
            });
            try {
                // Pool "A" specifically, not the process-wide check: the
                // manager always carries an untouched DEFAULT-POOL too,
                // which would otherwise mask "A" being full.
                await(() -> !localManager.poolsHaveCapacity(java.util.Set.of("A")));
                // Let the loop notice and enter its capacity-pause branch at
                // least once under the CURRENT (pre-jump) clock value.
                await(() -> !warnings.raised.isEmpty());

                mutableClock.advance(ConsumerSupervisor.STALL_THRESHOLD.plusSeconds(1));
                // Real-time capacity-pause ticks (ALL_FULL_PAUSE) keep firing
                // and now read the advanced clock, refreshing lastAlive()
                // past the point lastPoll() alone would read as stale.
                await(() -> localServer.stalledConsumers().isEmpty());
            } finally {
                filler.interrupt();
            }
        } finally {
            release.countDown();
            localServer.close();
        }
    }

    @Test
    @DisplayName("closing stops the router and gives up leadership")
    void closeReleasesLeadership() {
        var router = server(LeaderElection.Config.of("fc:leader"), config("q://1"));
        router.start();
        await(() -> router.activeLoops() == 1);

        router.close();
        server = null;

        assertThat(store.holder).as("a rolling restart fails over at once").isNull();
        assertThat(built).allSatisfy(consumer -> assertThat(consumer.closed).isTrue());
    }

    private RouterConfig config(String... queueUris) {
        return new RouterConfig(List.of(new PoolSpec("A", 2, 0)),
                java.util.Arrays.stream(queueUris).map(QueueConfig::of).toList());
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
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
        throw new AssertionError("condition not met within 10s");
    }

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

    private static final class FakeStore implements LockStore {
        volatile String holder;

        @Override
        public boolean acquire(String key, String value, Duration ttl) {
            if (holder == null) {
                holder = value;
                return true;
            }
            return holder.equals(value);
        }

        @Override
        public boolean refresh(String key, String value, Duration ttl) {
            return value.equals(holder);
        }

        @Override
        public void release(String key, String value) {
            if (value.equals(holder)) {
                holder = null;
            }
        }

        @Override
        public void ping() {
        }
    }

    /// Delivers exactly one non-empty batch, then empty forever. Used to set
    /// a loop's [ConsumerLoop#lastPoll] once (the "seed") and never again —
    /// once its fed pool is full, the loop never gets a chance to poll it
    /// empty either, since capacity is checked before every poll.
    private static final class OneShotThenEmptyConsumer implements Consumer {
        private final String id;
        private final AtomicBoolean sent = new AtomicBoolean();
        final AtomicBoolean delivered = new AtomicBoolean();

        OneShotThenEmptyConsumer(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) {
            if (sent.compareAndSet(false, true)) {
                delivered.set(true);
                return PollResult.of(List.of(message("seed", "A")));
            }
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

    private static final class RecordingWarnings implements Warnings {
        final List<String> raised = new CopyOnWriteArrayList<>();

        @Override
        public void raise(Severity severity, String category, String message) {
            raised.add(severity + " " + category + " " + message);
        }
    }
}

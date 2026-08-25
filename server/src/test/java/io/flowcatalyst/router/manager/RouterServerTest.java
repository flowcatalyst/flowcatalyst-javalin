package io.flowcatalyst.router.manager;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
        };
        return new RouterManager(tracker, warnings, clock, config -> {
            var pool = new Pool(config, mediator, recording, PoolMetrics.NO_OP, clock);
            pools.add(pool);
            return pool;
        });
    }

    private static QueuedMessage message(String id) {
        return QueuedMessage.of(
                new io.flowcatalyst.router.wire.Message(id, "", null, null,
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

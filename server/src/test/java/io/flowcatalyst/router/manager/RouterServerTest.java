package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.lifecycle.LifecycleLoops;
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
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
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
                        DispatchMode.IMMEDIATE),
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
        // The config-poll task (Router.java, RouterServer.parseConfigPollInterval) exists to
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
    @DisplayName("§5.5: applyConfiguration's fetch holds no lock, so a leadership loss racing a slow one still pauses polling promptly")
    void leadershipLossDuringSlowFetchStillPausesPromptly() throws InterruptedException {
        // The defect this pins: applyConfiguration() used to be synchronized
        // across the WHOLE call, fetch included. HttpConfigSource can retry
        // an unreachable config service for minutes, and applyConfiguration
        // is also the periodic config-poll task (A-10) — so a leadership
        // loss landing mid-fetch used to block loseLeadership() (also
        // synchronized) for however long the fetch took, leaving `running`
        // true and the poll loops delivering well past what §5.5 requires
        // ("losing leadership MUST pause polling").
        var configRef = new AtomicReference<RouterServer.ConfigSource>(RouterServer.ConfigSource.fixed(config("q://1")));
        RouterServer.ConfigSource dynamicSource = () -> configRef.get().fetch();
        election = new LeaderElection(LeaderElection.Config.of("fc:leader"), store, clock);
        server = new RouterServer(manager(), tracker, election, this::build, dynamicSource, warnings, clock,
                Duration.ofSeconds(1));
        server.start();
        await(() -> server.activeLoops() == 1);
        int buildsBeforeSlowFetch = built.size();

        var fetchStarted = new CountDownLatch(1);
        var fetchGate = new CountDownLatch(1);
        configRef.set(() -> {
            fetchStarted.countDown();
            try {
                fetchGate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(config("q://1"));
        });

        // Simulates the periodic config-poll housekeeping task calling
        // applyConfiguration() on its own thread while leadership is held.
        var fetchThread = Thread.ofVirtual().start(server::applyConfiguration);
        try {
            assertThat(fetchStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the slow fetch actually started").isTrue();

            // Drive the loss on its own thread too, bounded: if
            // applyConfiguration were still synchronized across the fetch,
            // this would block for as long as the fetch does, not fail fast.
            var loseThread = Thread.ofVirtual().start(() -> {
                store.holder = "someone-else";
                election.contendNow();
            });
            loseThread.join(Duration.ofMillis(500));

            assertThat(loseThread.isAlive())
                    .as("leadership loss must not block on the in-flight fetch").isFalse();
            assertThat(server.running()).as("§5.5: losing leadership MUST pause polling").isFalse();
            assertThat(server.activeLoops()).isZero();
        } finally {
            fetchGate.countDown();
            fetchThread.join(Duration.ofSeconds(2));
        }

        // The stale fetch, resolved after leadership was already lost, must
        // not resurrect anything: `apply` re-checks `running` itself.
        assertThat(server.activeLoops()).as("nothing restarted from the discarded, stale fetch").isZero();
        assertThat(built.size()).as("no new consumer built from it either")
                .isEqualTo(buildsBeforeSlowFetch);
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
                // The loop is still parked, untimed, on the capacity gate —
                // no periodic tick to refresh a stored instant any more
                // (ConsumerLoop#awaitCapacity is event-driven, 2026-09-07).
                // ConsumerLoop#lastAlive instead reports the *current*
                // instant for as long as it is genuinely still paused, which
                // reads past-the-jump immediately rather than waiting for a
                // wake that would never come while pool "A" stays full.
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
    @DisplayName("X-11/R-26: a replaced queue's NEW consumer is polled, and the OLD one is not")
    void replacedQueueSwapsLoopToNewConsumer() throws InterruptedException {
        var configRef = new AtomicReference<>(new RouterConfig(List.of(new PoolSpec("A", 2, 0)),
                List.of(new QueueConfig("q://1", "orders", 1, 30))));
        election = new LeaderElection(LeaderElection.Config.disabled(), store, clock);
        server = new RouterServer(manager(), tracker, election, this::build,
                () -> Optional.of(configRef.get()), warnings, clock, Duration.ofSeconds(1));

        server.start();
        await(() -> server.activeLoops() == 1);
        var first = built.getFirst();
        await(() -> first.polls.get() >= 1);
        int pollsAtSwap = first.polls.get();

        // Same queue name, different connections — a CHANGE, not a removal.
        configRef.set(new RouterConfig(List.of(new PoolSpec("A", 2, 0)),
                List.of(new QueueConfig("q://1", "orders", 4, 30))));
        server.applyConfiguration();

        await(() -> built.size() == 2);
        var second = built.get(1);
        assertThat(second).as("a genuinely new consumer object").isNotSameAs(first);
        await(() -> second.polls.get() >= 1);

        // EMPTY_POLL_PAUSE is 1s: if the old loop were still running it
        // would have polled again well within this margin.
        Thread.sleep(1_500);
        assertThat(first.polls.get()).as("the OLD loop's thread was interrupted, not merely orphaned")
                .isEqualTo(pollsAtSwap);
    }

    @Test
    @DisplayName("Q28: failed rebuilds accumulate, and a successful rebuild clears the count only once its consumer has polled")
    void restartCountClearsOnlyOnceTheReplacementPolls() throws InterruptedException {
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolatedTracker = new InFlightTracker(mutableClock);
        var localManager = new RouterManager(isolatedTracker, warnings, mutableClock, cfg ->
                new Pool(cfg, (msg, recordFailure) -> MediationOutcome.Success.of(200),
                        NO_OP_BROKER, PoolMetrics.NO_OP, mutableClock));
        var original = new FakeConsumer("orders");
        var toHandOut = new java.util.concurrent.ConcurrentLinkedQueue<Consumer>();
        toHandOut.add(original);
        RouterManager.ConsumerFactory factory = q -> Optional.ofNullable(toHandOut.poll());

        election = new LeaderElection(LeaderElection.Config.disabled(), store, mutableClock);
        var fastSupervisor = new ConsumerSupervisor(warnings, mutableClock, Duration.ofMillis(1));
        var localServer = new RouterServer(localManager, isolatedTracker, election, factory,
                RouterServer.ConfigSource.fixed(new RouterConfig(List.of(new PoolSpec("A", 2, 0)),
                        List.of(new QueueConfig("q://1", "orders", 1, 30)))),
                warnings, mutableClock, Duration.ofSeconds(1), fastSupervisor);
        try {
            localServer.start();
            await(() -> original.polls.get() >= 1);
            original.failPolls = true;
            mutableClock.advance(ConsumerSupervisor.STALL_THRESHOLD.plusSeconds(1));

            // Two ticks in which the factory cannot rebuild the consumer: the
            // count climbs (Q28 — a failed attempt is an attempt) and nothing
            // resets it, because nothing has polled.
            localServer.restartStalledLoops();
            assertThat(fastSupervisor.restartAttempts("orders")).isEqualTo(1);
            localServer.restartStalledLoops();
            assertThat(fastSupervisor.restartAttempts("orders")).isEqualTo(2);

            // A rebuild that succeeds does NOT clear the count by itself…
            var replacement = new FakeConsumer("orders");
            toHandOut.add(replacement);
            localServer.restartStalledLoops();
            await(() -> localManager.activeConsumer("orders").map(c -> c == replacement).orElse(false));
            assertThat(fastSupervisor.restartAttempts("orders"))
                    .as("built, but it has not polled yet — not recovered").isEqualTo(3);

            // …only the replacement polling successfully does, on the next tick.
            await(() -> replacement.polls.get() >= 1);
            localServer.restartStalledLoops();
            assertThat(fastSupervisor.restartAttempts("orders")).isEqualTo(0);
        } finally {
            localServer.close();
        }
    }

    @Test
    @DisplayName("R-26: a stalled loop is rebuilt without aborting what the OLD consumer is still holding")
    void stalledLoopRestartsWithoutAbortingInFlightWork() throws InterruptedException {
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var isolatedTracker = new InFlightTracker(mutableClock);
        var localPools = new CopyOnWriteArrayList<Pool>();
        var localManager = new RouterManager(isolatedTracker, warnings, mutableClock, cfg -> {
            var pool = new Pool(cfg, (msg, recordFailure) -> MediationOutcome.Success.of(200),
                    NO_OP_BROKER, PoolMetrics.NO_OP, mutableClock);
            localPools.add(pool);
            return pool;
        });
        var original = new FakeConsumer("orders");
        var toHandOut = new java.util.concurrent.ConcurrentLinkedQueue<Consumer>();
        toHandOut.add(original);
        RouterManager.ConsumerFactory factory = q -> Optional.ofNullable(toHandOut.poll());

        election = new LeaderElection(LeaderElection.Config.disabled(), store, mutableClock);
        var fastSupervisor = new ConsumerSupervisor(warnings, mutableClock, Duration.ofMillis(1));
        var localServer = new RouterServer(localManager, isolatedTracker, election, factory,
                RouterServer.ConfigSource.fixed(new RouterConfig(List.of(new PoolSpec("A", 2, 0)),
                        List.of(new QueueConfig("q://1", "orders", 1, 30)))),
                warnings, mutableClock, Duration.ofSeconds(1), fastSupervisor);
        try {
            localServer.start();
            await(() -> localServer.activeLoops() == 1);
            await(() -> original.polls.get() >= 1); // seeds lastPoll

            // Freeze progress: every further poll fails, so lastPoll stops
            // advancing — the shape a genuinely stuck consumer takes.
            original.failPolls = true;

            // An in-flight message this consumer polled, still owned when it
            // stalls — the case the fix exists for.
            isolatedTracker.register(new InFlightMessage("m1", "b1", "A", "orders",
                    mutableClock.instant(), mutableClock.instant(), "", "batch-1", "receipt-1", 0));

            mutableClock.advance(ConsumerSupervisor.STALL_THRESHOLD.plusSeconds(1));

            var replacement = new FakeConsumer("orders");
            toHandOut.add(replacement);
            localServer.restartStalledLoops();

            await(() -> localManager.activeConsumer("orders").map(c -> c == replacement).orElse(false));
            await(() -> replacement.polls.get() >= 1);

            assertThat(original.closed)
                    .as("not closed while an in-flight message from it is still tracked").isFalse();

            // `consumer()` resolves active-before-lingering (ruling 5), so
            // once the replacement is active the ack resolves through IT —
            // the documented, accepted imprecision for a same-name swap
            // (`docs/spec/router-specification.md` §5.1's closing paragraph:
            // "resolveConsumer... resolves a buffered message's ack through
            // whichever consumer is currently active for that name, not
            // necessarily the physical instance that polled it... harmless
            // when the change didn't swap the underlying broker connection").
            // What matters here is that it resolves and acks cleanly at all —
            // neither object is ever left silently unreachable.
            var broker = new QueueBroker(qid -> localManager.consumer(qid).orElse(null), isolatedTracker, mutableClock);
            broker.ack(QueuedMessage.of(new io.flowcatalyst.router.wire.Message("m1", "A", null, null,
                    io.flowcatalyst.router.wire.MediationType.HTTP, "https://x.test/h", null, false,
                    DispatchMode.IMMEDIATE), "b1", "receipt-1", "orders"));

            assertThat(replacement.acked).as("resolves through the now-active replacement").contains("m1");
            assertThat(original.acked).as("not the detached original").isEmpty();

            // The tracker entry is gone (the ack removed it), so retiring
            // finds nothing left referencing the original's queue any more.
            localManager.retireLingeringConsumers();
            assertThat(original.closed).as("closed once nothing references it any more").isTrue();
        } finally {
            localServer.close();
            localPools.forEach(Pool::close);
        }
    }

    @Test
    @DisplayName("FC_ROUTER_CONFIG_INTERVAL_SECONDS: unset/blank silently resolves to the 300s default")
    void parseConfigPollIntervalDefaultsSilently() {
        assertThat(RouterServer.parseConfigPollInterval(null)).isEqualTo(Duration.ofSeconds(300));
        assertThat(RouterServer.parseConfigPollInterval("")).isEqualTo(Duration.ofSeconds(300));
        assertThat(RouterServer.parseConfigPollInterval("  ")).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("FC_ROUTER_CONFIG_INTERVAL_SECONDS: a positive integer is honoured verbatim")
    void parseConfigPollIntervalHonoursAPositiveValue() {
        assertThat(RouterServer.parseConfigPollInterval("60")).isEqualTo(Duration.ofSeconds(60));
        assertThat(RouterServer.parseConfigPollInterval("1")).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("FC_ROUTER_CONFIG_INTERVAL_SECONDS: a set-but-invalid value falls back to 300s (Rust silently defaults; this WARNs)")
    void parseConfigPollIntervalFallsBackOnGarbage() {
        assertThat(RouterServer.parseConfigPollInterval("not-a-number")).isEqualTo(Duration.ofSeconds(300));
        assertThat(RouterServer.parseConfigPollInterval("0")).as("zero is not positive").isEqualTo(Duration.ofSeconds(300));
        assertThat(RouterServer.parseConfigPollInterval("-5")).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("A-10/R-31: the config-poll task Router.java composes actually fires at the env-configured interval")
    void configPollFiresAtTheConfiguredInterval() {
        // Mirrors exactly how Router.java composes this task:
        // RouterServer.parseConfigPollInterval(env.routerConfigIntervalRaw())
        // feeding a LifecycleLoops.Task named "config-poll". Kills the
        // "config interval ignored (constant used)" mutant: a regression
        // that silently used CONFIG_POLL_INTERVAL_DEFAULT (5 minutes)
        // instead of the parsed 1s value would never see three fetches
        // inside the 10s `await` budget below.
        var fetches = new AtomicInteger();
        RouterServer.ConfigSource countingSource = () -> {
            fetches.incrementAndGet();
            return Optional.of(config("q://1"));
        };
        election = new LeaderElection(LeaderElection.Config.disabled(), store, clock);
        server = new RouterServer(manager(), tracker, election, this::build, countingSource, warnings, clock,
                Duration.ofSeconds(1));
        server.start();
        await(() -> server.activeLoops() == 1);
        int fetchesAtStart = fetches.get();

        var loops = new LifecycleLoops();
        try {
            loops.start(List.of(new LifecycleLoops.Task("config-poll",
                    RouterServer.parseConfigPollInterval("1"), server::applyConfiguration)));

            await(() -> fetches.get() >= fetchesAtStart + 3);
        } finally {
            loops.close();
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
        /// Poll count, for asserting which loop is actually being ticked
        /// (X-11/R-26: the OLD consumer for a replaced/stalled queue must
        /// stop being polled the instant its loop is swapped, even though it
        /// stays open for ack/nack).
        final AtomicInteger polls = new AtomicInteger();
        /// Ids acked on THIS object — the assertion that a message still
        /// resolves ack on whichever consumer actually polled it, not
        /// whichever is active now.
        final List<String> acked = new CopyOnWriteArrayList<>();
        /// Once true, every poll throws instead of answering empty — how a
        /// test freezes this consumer's `lastPoll` heartbeat so it can be
        /// judged stalled without waiting out real time.
        volatile boolean failPolls;

        FakeConsumer(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) {
            polls.incrementAndGet();
            if (failPolls) {
                throw new RuntimeException("poll failed (test)");
            }
            return PollResult.empty();
        }

        @Override
        public boolean ack(QueuedMessage message) {
            acked.add(message.id());
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

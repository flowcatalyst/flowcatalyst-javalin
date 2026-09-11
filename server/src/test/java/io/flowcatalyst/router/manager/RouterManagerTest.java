package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.policy.RetryPolicy;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Mediator;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.ConsumerBuild;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Routing and pool resolution (`docs/spec/router.md` §3.3).
class RouterManagerTest {

    private final Clock clock = Clock.systemUTC();
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final RecordingWarnings warnings = new RecordingWarnings();
    private final List<RecordingPool> created = new ArrayList<>();
    private final RecordingConsumer source = new RecordingConsumer("queue-1");

    /// Background top-up threads a capacity test starts to keep a pool full
    /// (mirrors `ConsumerLoopTest#fillPool`) — interrupted in [#closePools]
    /// so none outlives its test.
    private final List<Thread> fillers = new CopyOnWriteArrayList<>();

    private final RouterManager manager =
            new RouterManager(tracker, warnings, clock, config -> {
                var recording = RecordingPool.of(config.code());
                created.add(recording);
                return recording.pool();
            });

    @AfterEach
    void closePools() {
        fillers.forEach(Thread::interrupt);
        // Unblock first so close() does not have to wait out its own
        // hand-back timeout for a mediator this test parked deliberately.
        created.forEach(recording -> recording.blocked().set(false));
        created.forEach(recording -> recording.pool().close());
        // Pools the manager synthesised or was handed directly (registerPool)
        // are its to close — a test that forgets one leaks its workers for
        // the life of the JVM.
        manager.close();
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

    // ── §7.1/§7.4: ack resolves by Consumer#identifier, not the config queue name ──

    @Test
    @DisplayName("ack resolves by Consumer#identifier, not the config queue name — the NATS shape "
            + "(`<stream>/<consumer>` != queueName, §7.1/§7.4) that dropped every ack in the router bench")
    void ackResolvesByConsumerIdentifierNotConfigQueueName() {
        // Mirrors the router bench's NATS run exactly: config queue name
        // "BENCH-1", consumer identifier "S1/router" (§7.4's
        // <stream>/<consumer> shape) — the mismatch that produced "ack
        // skipped: queue BENCH1/router is no longer registered" for every
        // one of 3,000 deliveries (bench/router/results/java-nats-q1-c1.server.log).
        var natsLike = new RecordingConsumer("S1/router");
        var pools = new CopyOnWriteArrayList<Pool>();
        // Two-step construction: the pool factory needs to resolve consumers
        // through the very manager it is building a pool for — the same
        // circularity [io.flowcatalyst.server.Router] resolves with
        // `manager.consumer(...)` inside a lambda handed to the manager's own
        // constructor.
        var holder = new RouterManager[1];
        var localManager = new RouterManager(tracker, Warnings.NO_OP, clock, cfg -> {
            var pool = new Pool(cfg, (msg, recordFailure) -> MediationOutcome.Success.of(200),
                    new QueueBroker(qid -> holder[0].consumer(qid).orElse(null), tracker, clock),
                    PoolMetrics.NO_OP, clock);
            pools.add(pool);
            return pool;
        });
        holder[0] = localManager;
        try {
            localManager.reconfigure(
                    new RouterConfig(List.of(), List.of(new QueueConfig("nats://host?stream=S1&consumer=router",
                            "BENCH-1", 0, 30))),
                    queue -> ConsumerBuild.of(natsLike));

            // A poll stamps QueueIdentifier from Identifier() (§7.1), never
            // the config queue name.
            var message = QueuedMessage.of(
                    new Message("m1", null, null, null, MediationType.HTTP, "https://x.test/h",
                            null, false, DispatchMode.IMMEDIATE),
                    "b1", "receipt-b1", natsLike.identifier());

            localManager.route(List.of(message), natsLike);

            await(() -> !natsLike.acked.isEmpty());
            // The load-bearing assertion: the fake consumer that actually
            // polled the message receives the ack exactly once. Resolving by
            // the config name instead ("BENCH-1") finds nothing, logs "ack
            // skipped: queue ... is no longer registered", and this list
            // stays empty forever.
            assertThat(natsLike.acked).as("ack(receipt) reaches the polling consumer, by identifier")
                    .containsExactly("m1");

            // After a reconfigure drops the queue, the identifier index no
            // longer resolves it once nothing lingers on the tracker's behalf.
            localManager.reconfigure(RouterConfig.EMPTY, queue -> ConsumerBuild.FAILED);
            localManager.retireLingeringConsumers();
            assertThat(localManager.consumer("S1/router"))
                    .as("gone from the identifier index once retired").isEmpty();
        } finally {
            pools.forEach(Pool::close);
        }
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
    @DisplayName("capacity is judged per pool, so an unrelated idle pool does not mask a full one "
            + "(`docs/spec/router.md` §2.4, §6) — this is the per-consumer half; ConsumerLoopTest pins the "
            + "consumer-level pause it enables")
    void capacityIsPerPoolNotProcessWide() {
        var busy = registerPool("busy");
        registerPool("idle");
        assertThat(manager.poolsHaveCapacity(Set.of("busy"))).as("nothing routed yet").isTrue();

        fillToCapacity(busy);

        // The rule the router used to get wrong: a full pool must be
        // reported full when asked about specifically, regardless of
        // whether some OTHER pool the caller did not name has room.
        assertThat(manager.poolsHaveCapacity(Set.of("busy")))
                .as("busy is genuinely full").isFalse();
        assertThat(manager.poolsHaveCapacity(Set.of("idle")))
                .as("idle was never asked about busy").isTrue();
        // "at least one of these" semantics: naming both still finds room,
        // because idle has it.
        assertThat(manager.poolsHaveCapacity(Set.of("busy", "idle"))).isTrue();
        // The old, process-wide rule this replaces would still see room
        // (via idle) and is still available for a caller with nothing more
        // specific to judge against.
        assertThat(manager.anyPoolHasCapacity()).isTrue();
    }

    @Test
    @DisplayName("with no pools at all there is no capacity")
    void noPoolsMeansNoCapacity() {
        assertThat(manager.anyPoolHasCapacity()).isFalse();
        assertThat(manager.poolsHaveCapacity(Set.of("anything"))).isFalse();
    }

    // ── R-13/R-16: strict routing gate ──────────────────────────────────

    @Test
    @DisplayName("R-13/R-16: strict gate on — a message with no poolCode is acked as malformed, never routed")
    void strictGateRejectsMissingPoolCode() {
        var strict = strictManager();
        var pool = registerPoolOn(strict, RouterManager.DEFAULT_POOL);
        strict.registerConsumer(source);

        strict.route(List.of(malformed("m1", null, DispatchMode.IMMEDIATE, null)), source);

        assertThat(source.acked).containsExactly("m1");
        assertThat(pool.delivered()).as("never reaches a pool").isEmpty();
        assertThat(tracker.size()).isZero();
        assertThat(warnings.raised).singleElement().asString()
                .contains("WARNING").contains("CONFIGURATION").contains("m1");
    }

    @Test
    @DisplayName("R-13/R-16: strict gate on — dispatchMode absent on the wire is acked as malformed")
    void strictGateRejectsAbsentDispatchMode() {
        var strict = strictManager();
        var pool = registerPoolOn(strict, RouterManager.DEFAULT_POOL);
        strict.registerConsumer(source);

        // A group IS present: NEXT_ON_ERROR (the default an absent
        // dispatchMode normalises to) would also be ordered-with-a-group and
        // route fine, so this isolates "dispatchMode absent" from the
        // "ordered with no group" rule — without the group, both rules would
        // independently call it malformed and the test would not tell which
        // one actually fired.
        strict.route(List.of(malformed("m2", "A", null, "g1")), source);

        assertThat(source.acked).containsExactly("m2");
        assertThat(pool.delivered()).isEmpty();
        assertThat(warnings.raised).singleElement().asString().contains("m2");
    }

    @Test
    @DisplayName("R-13/R-16: strict gate on — an ordered dispatchMode with no messageGroupId is acked as malformed")
    void strictGateRejectsOrderedWithNoGroup() {
        var strict = strictManager();
        var pool = registerPoolOn(strict, RouterManager.DEFAULT_POOL);
        strict.registerConsumer(source);

        strict.route(List.of(malformed("m3", "A", DispatchMode.BLOCK_ON_ERROR, null)), source);

        assertThat(source.acked).containsExactly("m3");
        assertThat(pool.delivered()).isEmpty();
        assertThat(warnings.raised).singleElement().asString().contains("m3");
    }

    @Test
    @DisplayName("R-13/R-16: strict gate off (default) — the same three messages route exactly as today")
    void strictGateOffKeepsThePreRulingFallbacks() {
        // `manager` (the class field) has the gate off by construction.
        var pool = registerPool(RouterManager.DEFAULT_POOL);
        manager.registerConsumer(source);

        manager.route(List.of(
                malformed("m1", null, DispatchMode.IMMEDIATE, null),
                malformed("m2", "", null, null),
                malformed("m3", "", DispatchMode.BLOCK_ON_ERROR, null)), source);

        await(() -> pool.delivered().containsAll(List.of("m1", "m2", "m3")));
        assertThat(source.acked).as("nothing is dropped when the gate is off").isEmpty();
    }

    // ── R-59: idle eviction of synthesised pools ────────────────────────

    @Test
    @DisplayName("R-59: a synthesised fallback pool idle past the TTL is evicted and closed")
    void evictsAnIdleSynthesisedPool() {
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var evictionCreated = new ArrayList<RecordingPool>();
        var eviction = new RouterManager(new InFlightTracker(mutableClock), warnings, mutableClock, config -> {
            var recording = RecordingPool.of(config.code());
            evictionCreated.add(recording);
            return recording.pool();
        });
        var consumer = new RecordingConsumer("queue-1");
        eviction.registerConsumer(consumer);

        eviction.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL")), consumer);
        await(() -> !evictionCreated.isEmpty());
        var synth = evictionCreated.getFirst();
        await(() -> synth.delivered().contains("m1"));
        await(() -> synth.pool().queueSize() == 0 && synth.pool().activeWorkers() == 0);

        mutableClock.advance(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL.plusMinutes(1));
        var evicted = eviction.evictIdleSynthesisedPools(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL);

        assertThat(evicted).isOne();
        assertThat(eviction.pools()).doesNotContainKey("acme-DEFAULT-POOL");
        // Not just dropped from the map — actually stopped: a submission
        // against the same (now orphaned) Pool object must never reach the
        // mediator again.
        synth.pool().submit(message("m2", "b2", "acme-DEFAULT-POOL"));
        assertThat(synth.delivered()).as("a closed pool must not still be accepting deliveries")
                .doesNotContain("m2");
    }

    @Test
    @DisplayName("R-59: a synthesised pool NOT yet idle past the TTL is kept, even with no work at all")
    void keepsASynthesisedPoolNotYetIdle() {
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var evictionCreated = new ArrayList<RecordingPool>();
        var eviction = new RouterManager(new InFlightTracker(mutableClock), warnings, mutableClock, config -> {
            var recording = RecordingPool.of(config.code());
            evictionCreated.add(recording);
            return recording.pool();
        });
        var consumer = new RecordingConsumer("queue-1");
        eviction.registerConsumer(consumer);

        eviction.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL")), consumer);
        await(() -> !evictionCreated.isEmpty());
        await(() -> evictionCreated.getFirst().delivered().contains("m1"));
        await(() -> evictionCreated.getFirst().pool().queueSize() == 0);

        // Short of the TTL, and holding no work either — only the age
        // matters here.
        mutableClock.advance(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL.minusMinutes(1));
        var evicted = eviction.evictIdleSynthesisedPools(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL);

        assertThat(evicted).as("younger than the TTL — not idle yet").isZero();
        assertThat(eviction.pools()).containsKey("acme-DEFAULT-POOL");
    }

    @Test
    @DisplayName("R-59: a synthesised pool still holding work is skipped, however idle its last route")
    void keepsASynthesisedPoolStillHoldingWork() {
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var evictionCreated = new ArrayList<RecordingPool>();
        var eviction = new RouterManager(new InFlightTracker(mutableClock), warnings, mutableClock, config -> {
            var recording = RecordingPool.of(config.code());
            recording.blocked().set(true);
            evictionCreated.add(recording);
            return recording.pool();
        });
        var consumer = new RecordingConsumer("queue-1");
        eviction.registerConsumer(consumer);

        eviction.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL")), consumer);
        await(() -> !evictionCreated.isEmpty());
        var synth = evictionCreated.getFirst();
        await(() -> synth.pool().activeWorkers() == 1);

        mutableClock.advance(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL.plusMinutes(1));
        var evicted = eviction.evictIdleSynthesisedPools(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL);

        assertThat(evicted).as("still holding work — the drain rules give it another tick first").isZero();
        assertThat(eviction.pools()).containsKey("acme-DEFAULT-POOL");
        synth.blocked().set(false);
    }

    @Test
    @DisplayName("R-59: an explicitly configured pool named like a synthesised one is never evicted")
    void keepsAnExplicitlyConfiguredPoolWithTheSynthSuffix() {
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var eviction = new RouterManager(new InFlightTracker(mutableClock), warnings, mutableClock,
                config -> RecordingPool.of(config.code()).pool());
        var consumer = new RecordingConsumer("queue-1");
        eviction.registerConsumer(consumer);
        // Configured, not synthesised — reconfigure() is what records
        // configuredPoolCodes; the synthesis path in poolFor() never runs
        // for a code the configuration already names.
        eviction.reconfigure(new RouterConfig(List.of(new PoolSpec("acme-DEFAULT-POOL", 4, 0)), List.of()),
                q -> ConsumerBuild.FAILED);

        eviction.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL")), consumer);
        await(() -> eviction.pools().get("acme-DEFAULT-POOL").queueSize() == 0);

        mutableClock.advance(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL.plusMinutes(1));
        var evicted = eviction.evictIdleSynthesisedPools(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL);

        assertThat(evicted).as("the synthesis mechanism must never override a real config entry").isZero();
        assertThat(eviction.pools()).containsKey("acme-DEFAULT-POOL");
    }

    @Test
    @DisplayName("R-59: a pool evicted for being idle re-synthesises on the next message naming it")
    void resynthesisesAfterEviction() {
        var mutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var evictionCreated = new ArrayList<RecordingPool>();
        var eviction = new RouterManager(new InFlightTracker(mutableClock), warnings, mutableClock, config -> {
            var recording = RecordingPool.of(config.code());
            evictionCreated.add(recording);
            return recording.pool();
        });
        var consumer = new RecordingConsumer("queue-1");
        eviction.registerConsumer(consumer);
        eviction.route(List.of(message("m1", "b1", "acme-DEFAULT-POOL")), consumer);
        await(() -> evictionCreated.size() == 1);
        await(() -> evictionCreated.getFirst().pool().queueSize() == 0);
        mutableClock.advance(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL.plusMinutes(1));
        assertThat(eviction.evictIdleSynthesisedPools(RouterManager.DEFAULT_SYNTH_POOL_IDLE_TTL)).isOne();

        eviction.route(List.of(message("m2", "b2", "acme-DEFAULT-POOL")), consumer);

        await(() -> evictionCreated.size() == 2);
        await(() -> evictionCreated.get(1).delivered().contains("m2"));
    }

    // ── §2.1: a redelivery kicks a dead drainer back to life ────────────

    @Test
    @DisplayName("§2.1: a redelivery of a buffered ordered message resumes its pool's dead drainer")
    void redeliveryResumesADeadDrainer() {
        var delivered = new CopyOnWriteArrayList<String>();
        var interrupted = new AtomicBoolean();
        // A drainer thread dying mid-backoff (an interrupted slot wait or a
        // cancelled backoff) re-fronts the head and releases the drainer
        // flag WITHOUT emptying the group's buffer — nothing is draining it
        // any more until something kicks it back to life.
        Mediator selfInterrupting = (msg, recordFailure) -> {
            delivered.add(msg.id());
            if ("m0".equals(msg.id()) && interrupted.compareAndSet(false, true)) {
                Thread.currentThread().interrupt();
                return new MediationOutcome.RateLimited(1);
            }
            return MediationOutcome.Success.of(200);
        };
        var slowBackoff = new Pool.Backoffs(
                new RetryPolicy(List.of(Duration.ofSeconds(60)), Duration.ofSeconds(60), Duration.ofSeconds(60), 12),
                new RetryPolicy(List.of(), Duration.ofSeconds(60), Duration.ofSeconds(60), 12));
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
        var deadDrainerPool = new Pool(new Pool.Config("G-POOL", 4, 0), slowBackoff, selfInterrupting, noOp,
                PoolMetrics.NO_OP, clock);
        manager.registerPool("G-POOL", deadDrainerPool);
        manager.registerConsumer(source);

        manager.route(List.of(ordered("m0", "b0", "G-POOL", "g1"), ordered("m1", "b1", "G-POOL", "g1")), source);

        await(() -> interrupted.get());
        await(() -> deadDrainerPool.queueSize() == 2);
        // Give any wrongly-still-running drainer a moment it would use.
        try {
            Thread.sleep(Duration.ofMillis(50));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(deadDrainerPool.queueSize()).as("still buffered — the drainer died, it did not finish")
                .isEqualTo(2);

        // The broker redelivers m0 (same application id, same broker id —
        // not an external requeue).
        manager.route(List.of(ordered("m0", "b0", "G-POOL", "g1")), source);

        // Await the effect asserted, not a proxy for it: the buffer empties when
        // the drainer TAKES m1, before the mediator (which records it) runs —
        // under full-suite load the assertion could land in that gap.
        await(() -> deadDrainerPool.queueSize() == 0 && delivered.size() == 3);
        assertThat(delivered).as("both messages eventually delivered, in order")
                .containsExactly("m0", "m0", "m1");
        deadDrainerPool.close();
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private void fillToCapacity(RecordingPool recording) {
        recording.blocked().set(true);
        var code = recording.pool().config().code();
        var filler = Thread.ofVirtual().start(() -> {
            var n = 0;
            while (!Thread.currentThread().isInterrupted()) {
                if (manager.poolsHaveCapacity(Set.of(code))) {
                    recording.pool().submit(message("filler-" + n++, "filler-" + n, code));
                } else {
                    Thread.onSpinWait();
                }
            }
        });
        fillers.add(filler);
        await(() -> !manager.poolsHaveCapacity(Set.of(code)));
    }

    private RouterManager strictManager() {
        return new RouterManager(tracker, warnings, clock, config -> {
            var recording = RecordingPool.of(config.code());
            created.add(recording);
            return recording.pool();
        }, true);
    }

    private RecordingPool registerPoolOn(RouterManager targetManager, String code) {
        var recording = RecordingPool.of(code);
        targetManager.registerPool(code, recording.pool());
        return recording;
    }

    private static QueuedMessage malformed(String id, String poolCode, DispatchMode mode, String group) {
        return QueuedMessage.of(
                new Message(id, poolCode, null, null, MediationType.HTTP, "https://x.test/h", group, false, mode),
                "b-" + id, "receipt-" + id, "queue-1");
    }

    private static QueuedMessage ordered(String id, String brokerId, String poolCode, String group) {
        return QueuedMessage.of(
                new Message(id, poolCode, null, null, MediationType.HTTP, "https://x.test/h",
                        group, false, DispatchMode.BLOCK_ON_ERROR),
                brokerId, "receipt-" + brokerId, "queue-1");
    }

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
    ///
    /// @param blocked held true to keep every delivery parked open, the way
    ///                a test fills a pool to capacity: with instant delivery
    ///                the queue drains as fast as it fills and never reaches
    ///                capacity (mirrors `ConsumerLoopTest`'s `deliveryBlocked`).
    private record RecordingPool(Pool pool, List<String> delivered, AtomicBoolean blocked) {

        static RecordingPool of(String code) {
            var delivered = new CopyOnWriteArrayList<String>();
            var blocked = new AtomicBoolean();
            Mediator mediator = (message, recordFailure) -> {
                while (blocked.get()) {
                    Thread.sleep(Duration.ofMillis(5));
                }
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
                    delivered, blocked);
        }

        int queueSize() {
            return pool.queueSize();
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

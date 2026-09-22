package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.concurrent.Concurrently;
import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.ConsumerBuild;
import io.flowcatalyst.router.queue.Publisher;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/// Decides where each polled message goes, and hands it to a pool.
///
/// Everything upstream of this is a queue; everything downstream is a pool.
/// The manager owns the two things that sit between them: **ownership** (via
/// the in-flight tracker, which decides whether an arriving copy is new, a
/// redelivery, or a duplicate from elsewhere) and **pool resolution**
/// (`docs/spec/router.md` §3.3).
public final class RouterManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RouterManager.class);

    /// Where a message with no usable pool code goes. Always present — see
    /// [#poolFor].
    public static final String DEFAULT_POOL = "DEFAULT-POOL";

    /// Per-client fallback pools are named `{clientIdentifier}-DEFAULT-POOL`
    /// and are synthesised on demand (owner ruling, `docs/spec/router.md`
    /// §2.6). The router's config comes from an external service that will
    /// not know about them, so a message naming one must not be treated as
    /// naming an unknown pool.
    static final String DEFAULT_POOL_SUFFIX = "-" + DEFAULT_POOL;

    /// Delay on a nack when there is no pool at all to take the message
    /// (spec constant 10).
    static final Duration NO_POOL_NACK_DELAY = Duration.ofSeconds(5);

    /// Per-queue outstanding-deferral budget when none is configured
    /// (`FC_ROUTER_DEFERRAL_BUDGET`, owner ruling 2026-09-22, hand-off §1,
    /// §7, raised 5000 → 15000 the same day in the catch-up addendum):
    /// sized against SQS FIFO's 20,000 in-flight ceiling, which a deferred
    /// message counts toward.
    public static final int DEFAULT_DEFERRAL_BUDGET = 15000;

    /// How long a reconfigure will spend building consumers before carrying
    /// on without the stragglers. They are reported as failures and retried
    /// on the next reconfigure.
    static final Duration CONSUMER_BUILD_TIMEOUT = Duration.ofSeconds(30);

    /// Default idle TTL for a synthesised `{client}-DEFAULT-POOL` (R-59,
    /// `docs/spec/router.md` §2.2, §9's config table): used whenever
    /// `FC_ROUTER_SYNTH_POOL_IDLE_SECS` is unset or zero — the spec is
    /// explicit that 0/unset means "the implementation's own default," not
    /// "never evict".
    public static final Duration DEFAULT_SYNTH_POOL_IDLE_TTL = Duration.ofHours(1);

    private final Map<String, Pool> pools = new ConcurrentHashMap<>();

    /// Pools removed from routing by [#applyPools] but not yet [Pool#drained]
    /// (X-11, `docs/spec/router-completion.md` §2 ruling 6). Kept apart from
    /// [#pools] so a reconfigure that stops routing to a pool never blocks on
    /// it finishing — [#allPools] is the merged view the monitoring surface
    /// reads instead.
    private final Map<String, Pool> drainingPools = new ConcurrentHashMap<>();

    private final Map<String, Consumer> consumers = new ConcurrentHashMap<>();

    /// Every currently active consumer, ALSO indexed by [Consumer#identifier],
    /// maintained in parallel with [#consumers] on every put/remove
    /// (`docs/spec/router.md` §7.1/§7.4). Ack/nack resolution (the wire's
    /// `QueueIdentifier`, §7.1) must key on `identifier()`, which for a
    /// Postgres or SQS backend equals the config queue name but for NATS is
    /// `<stream>/<consumer>` (§7.4) — a distinct string. [#consumers] stays
    /// name-keyed because reconfigure's wanted-set diffing (§8.2) and
    /// [#consumerNames]/[#activeConsumer] operate on the config name; this
    /// index is the only structure [#consumer] (ack/nack resolution) reads.
    private final Map<String, Consumer> consumersByIdentifier = new ConcurrentHashMap<>();

    /// A consumer [#stopConsumer] detached, plus when. Kept resolvable by
    /// [#consumer] until [#retireLingeringConsumers] finds nothing in the
    /// tracker still referencing its queue (`docs/spec/router-completion.md`
    /// §2 ruling 5, X-11/R-26) — a message buffered or mid-delivery when its
    /// queue disappeared from config must still be able to ack/nack on the
    /// object that actually polled it.
    ///
    /// A [ConcurrentLinkedDeque] rather than one entry
    /// per name: a queue can in principle be replaced again before the first
    /// replacement's predecessor has finished lingering, and every one of
    /// them still owes something an ack. [#consumer] resolves the most
    /// recently detached — the same imprecision the specification documents
    /// for a **changed** queue (§5.1): harmless unless the change also swapped
    /// the underlying broker connection.
    private final Map<String, ConcurrentLinkedDeque<Lingering>> lingeringConsumers =
            new ConcurrentHashMap<>();

    /// One detached consumer, and when it stopped being the active one for
    /// its queue — the instant [InFlightTracker#countForQueue] checks entries
    /// against.
    private record Lingering(Consumer consumer, Instant detachedAt) {
    }

    /// The configuration each running consumer was built from, so a change
    /// can be detected without asking the consumer to describe itself.
    private final Map<String, QueueConfig> queueConfigs = new ConcurrentHashMap<>();

    /// Queue (config) names currently in a missing-streak — [ConsumerBuild.Missing]
    /// answered on the most recent build attempt (owner ruling 2026-09-11,
    /// `docs/spec/router.md` §7.2). Logged once, on the transition into the
    /// streak, and cleared the moment the queue is built successfully or the
    /// configuration stops naming it — so the same queue disappearing and
    /// reappearing later logs its own "does not exist yet" INFO again,
    /// rather than staying silently suppressed by a streak from a previous
    /// life.
    private final Set<String> missingQueues = ConcurrentHashMap.newKeySet();

    /// The one INFO line a missing queue gets — reused verbatim by
    /// [ConsumerLoop] when a previously-running queue disappears mid-poll,
    /// so an operator sees identical wording for the same fact regardless of
    /// which path noticed it.
    static final String MISSING_QUEUE_MESSAGE =
            "queue does not exist yet; not consuming it; rechecked at the next config sync";

    /// When a message was last routed to each pool — the clock
    /// [#evictIdleSynthesisedPools] ages a synthesised pool against (R-59).
    /// Kept for every pool, not just synthesised ones, because it costs
    /// nothing extra and keeps the update site (inside [#submit]) from
    /// having to know which pools are eligible for eviction.
    private final Map<String, Instant> lastRoutedAt = new ConcurrentHashMap<>();

    /// Pool codes the last applied configuration actually named — checked by
    /// [#evictIdleSynthesisedPools] so an explicitly configured pool whose
    /// code happens to end in the synthesised suffix is never evicted out
    /// from under the configuration that put it there.
    private volatile Set<String> configuredPoolCodes = Set.of();

    /// One deferral ledger per consumer, keyed by [Consumer#identifier] —
    /// the same key [#consumersByIdentifier] uses, since a deferral is
    /// booked against whichever consumer's `queueId()` the deferred message
    /// carried (`docs/spec/router-hol-deferral.md` §1). Created when a
    /// consumer becomes active ([#registerConsumer], [#replaceConsumer], the
    /// `Built` case of [#applyConsumers]) and dropped when it stops being one
    /// ([#stopConsumer]) — a deferral landing for an identifier this map no
    /// longer holds is simply dropped by [#noteDeferral]: nothing polls that
    /// queue any more, so nothing needs its ledger.
    private final Map<String, DeferralLedger> deferralLedgers = new ConcurrentHashMap<>();

    /// [ConsumerLoop#hasRoom]'s budget clause: how many deferrals one queue's
    /// consumer may have outstanding before a full destination set actually
    /// pauses it (§1).
    private final int deferralBudget;

    private final InFlightTracker tracker;
    private final Warnings warnings;
    private final Clock clock;
    private final PoolFactory poolFactory;
    private final AtomicLong batchCounter = new AtomicLong();

    /// Wakes a [ConsumerLoop] parked in [ConsumerLoop#awaitCapacity] the
    /// moment a pool's capacity changes, rather than it polling on a fixed
    /// interval (`docs/spec/router.md` §3.2). Every pool this manager adds is
    /// wired to signal it (see [#addPool]); a reconfigure or eviction that
    /// adds, removes, or drains a pool also signals it directly, since that
    /// too can change what [#anyPoolHasCapacity]/[#poolsHaveCapacity] answer.
    private final CapacityGate capacityGate = new CapacityGate();

    /// R-13/R-16: with the gate on, a message reaching the router with no
    /// usable `poolCode`, no `dispatchMode` on the wire, or an ordered
    /// `dispatchMode` with no `messageGroupId` is malformed — ACKed without
    /// delivery rather than defaulted (`docs/spec/router.md` §2.3). Off
    /// (default) everywhere until every producer is confirmed compliant.
    private final boolean strictRouting;

    /// Concurrency given to a pool the configuration named without one
    /// (spec constant 2).
    public static final int DEFAULT_POOL_CONCURRENCY = 20;

    /// Builds a pool from its configuration.
    @FunctionalInterface
    public interface PoolFactory {
        Pool create(Pool.Config config);
    }

    /// Builds a consumer for a configured queue — a sealed three-way outcome
    /// (`docs/spec/router.md` §7.1/§7.2, owner ruling 2026-09-11):
    /// [ConsumerBuild.Failed] means the queue could not be built — an
    /// unknown URI scheme, an unreachable broker — and the reconfigure
    /// carries on with the rest; [ConsumerBuild.Missing] means the queue is
    /// simply not there YET (an SQS queue Integral's control plane lists but
    /// has not created), which is not a failure and must never be treated as
    /// one.
    @FunctionalInterface
    public interface ConsumerFactory {
        ConsumerBuild create(QueueConfig config);
    }

    public RouterManager(InFlightTracker tracker, Warnings warnings, Clock clock, PoolFactory poolFactory) {
        this(tracker, warnings, clock, poolFactory, false);
    }

    public RouterManager(InFlightTracker tracker, Warnings warnings, Clock clock, PoolFactory poolFactory,
                         boolean strictRouting) {
        this(tracker, warnings, clock, poolFactory, strictRouting, DEFAULT_DEFERRAL_BUDGET);
    }

    public RouterManager(InFlightTracker tracker, Warnings warnings, Clock clock, PoolFactory poolFactory,
                         boolean strictRouting, int deferralBudget) {
        this.tracker = tracker;
        this.warnings = warnings;
        this.clock = clock;
        this.poolFactory = poolFactory;
        this.strictRouting = strictRouting;
        this.deferralBudget = deferralBudget > 0 ? deferralBudget : DEFAULT_DEFERRAL_BUDGET;
    }

    public void registerPool(String code, Pool pool) {
        addPool(code, pool);
    }

    /// The one place a pool is added to [#pools]: wires it to
    /// [#capacityGate] so its own crossing back under capacity wakes a
    /// parked [ConsumerLoop], and signals once for the addition itself —
    /// a brand new pool can turn "no pools at all" or "every pool full"
    /// into "there is room" just by existing. Also wires [#noteDeferral] so
    /// every deferral this pool hands back is booked on its source queue's
    /// ledger (§1).
    private void addPool(String code, Pool pool) {
        pools.put(code, pool);
        pool.onCapacityFreed(capacityGate::signal);
        pool.onDeferral(this::noteDeferral);
        capacityGate.signal();
    }

    /// Package-private: only [ConsumerLoop] parks on this.
    CapacityGate capacityGate() {
        return capacityGate;
    }

    /// The per-consumer deferral ledger this manager keeps for
    /// [ConsumerLoop]'s budget clause and wake-up (§1) — created on demand so
    /// a loop reading its own, currently-active consumer's ledger never finds
    /// nothing there, even though the write side ([#noteDeferral]) resolves
    /// strictly by lookup and drops a deferral for an identifier with none.
    DeferralLedger deferralLedger(String identifier) {
        return deferralLedgers.computeIfAbsent(identifier, ignored -> new DeferralLedger());
    }

    /// How many deferrals one queue's consumer may have outstanding before
    /// [ConsumerLoop#hasRoom]'s budget clause runs out (§1, §7
    /// `FC_ROUTER_DEFERRAL_BUDGET`).
    int deferralBudget() {
        return deferralBudget;
    }

    /// Every pool's [Pool#onDeferral] observer (§1): books the deferral's
    /// return time on the ledger of the consumer that owns `queueIdentifier`
    /// — the consumer that actually polled the deferred message, resolved
    /// the same way ack/nack resolution is ([#consumer], by
    /// [Consumer#identifier]). An identifier with no active ledger (the
    /// consumer was deregistered between routing and this call completing)
    /// is dropped without error: nothing polls that queue any more.
    private void noteDeferral(String queueIdentifier, Instant returnAt) {
        var ledger = deferralLedgers.get(queueIdentifier);
        if (ledger != null) {
            ledger.add(returnAt);
        }
    }

    public void registerConsumer(Consumer consumer) {
        consumers.put(consumer.identifier(), consumer);
        consumersByIdentifier.put(consumer.identifier(), consumer);
        deferralLedgers.putIfAbsent(consumer.identifier(), new DeferralLedger());
    }

    /// Resolves a consumer for ack/nack **by [Consumer#identifier]** — the
    /// wire's `QueueIdentifier` stamped on every polled message (§7.1) — the
    /// active one first, then — so a message buffered or in flight on a queue
    /// that has since been removed or changed can still settle — the most
    /// recently detached lingering one carrying that identifier
    /// (`docs/spec/router-completion.md` §2 ruling 5).
    ///
    /// Callers pass `message.queueId()` or an [io.flowcatalyst.router.inflight.InFlightMessage]'s
    /// `queueIdentifier()` — never the config queue name; [#activeConsumer] is
    /// the name-keyed, routing-only view a poll loop or a config-driven
    /// caller must use instead. Resolving a lingering consumer here must
    /// never be read as "this queue is still being polled".
    public Optional<Consumer> consumer(String identifier) {
        var active = consumersByIdentifier.get(identifier);
        if (active != null) {
            return Optional.of(active);
        }
        return lingeringConsumers.values().stream()
                .flatMap(Collection::stream)
                .filter(lingering -> lingering.consumer().identifier().equals(identifier))
                .max(Comparator.comparing(Lingering::detachedAt))
                .map(Lingering::consumer);
    }

    /// The consumer currently being polled for `queueId`, or empty when
    /// nothing is — never a lingering one. What
    /// [RouterServer#syncLoops] judges a poll loop's continued existence
    /// against; [#consumer] is the ack/nack-resolution superset that must not
    /// be used for that decision, or a removed queue's loop would poll its
    /// detached consumer forever.
    public Optional<Consumer> activeConsumer(String queueId) {
        return Optional.ofNullable(consumers.get(queueId));
    }

    /// The configuration a currently-active queue was built from, for the
    /// stall supervisor ([RouterServer#restartStalledLoops]) to rebuild a
    /// consumer from without RouterServer having to keep its own copy.
    public Optional<QueueConfig> queueConfig(String queueName) {
        return Optional.ofNullable(queueConfigs.get(queueName));
    }

    /// Swaps the active consumer for `queueName`, detaching whatever was
    /// there to the lingering set rather than closing it
    /// (`docs/spec/router-completion.md` §2 ruling 5, R-26): the stall
    /// supervisor's replacement, not a reconfigure. An in-flight delivery the
    /// old consumer still holds keeps running and acks/nacks on its own
    /// object; [#retireLingeringConsumers] closes it once the tracker holds
    /// nothing more for its queue.
    public void replaceConsumer(String queueName, Consumer replacement) {
        var old = consumers.put(queueName, replacement);
        consumersByIdentifier.put(replacement.identifier(), replacement);
        deferralLedgers.putIfAbsent(replacement.identifier(), new DeferralLedger());
        if (old != null) {
            // Only drop the identifier entry if it is still THIS old
            // consumer's — a replacement that happens to share an identifier
            // with its predecessor (Postgres/SQS, where identifier ==
            // queueName) must not have the `put` above undone by a stale
            // removal.
            consumersByIdentifier.remove(old.identifier(), old);
            if (!old.identifier().equals(replacement.identifier())) {
                deferralLedgers.remove(old.identifier());
            }
            linger(queueName, old);
        }
    }

    private void linger(String queueName, Consumer consumer) {
        lingeringConsumers.computeIfAbsent(queueName, ignored -> new ConcurrentLinkedDeque<>())
                .addLast(new Lingering(consumer, clock.instant()));
    }

    /// Closes and forgets every lingering consumer whose queue the tracker no
    /// longer references from before it detached (`docs/spec/router-completion.md`
    /// §2 ruling 5) — housekeeping, alongside [#closeDrainedPools].
    ///
    /// @return how many were retired
    public int retireLingeringConsumers() {
        var retired = 0;
        for (var entry : List.copyOf(lingeringConsumers.entrySet())) {
            var queueName = entry.getKey();
            var deque = entry.getValue();
            var it = deque.iterator();
            while (it.hasNext()) {
                var candidate = it.next();
                // The tracker's entries carry the consumer's IDENTIFIER
                // (§7.1's `QueueIdentifier`), not the config queue name this
                // deque is keyed by — for NATS the two differ (§7.4), and
                // comparing against `queueName` here would always count zero
                // and retire (close) a lingering consumer while it still
                // owed acks/nacks for genuinely in-flight messages.
                if (tracker.countForQueue(candidate.consumer().identifier(), candidate.detachedAt()) == 0) {
                    it.remove();
                    closeQuietly(candidate.consumer());
                    retired++;
                }
            }
            if (deque.isEmpty()) {
                // Mutated in place above — this drops the now-empty deque
                // itself so the map does not keep one entry per queue name
                // ever detached. A concurrent [#linger] landing between the
                // emptiness check and this call loses its addition to a
                // remove that targets the exact (by-reference) deque it
                // raced, which `Map#remove(key, value)` guards against.
                lingeringConsumers.remove(queueName, deque);
            }
        }
        return retired;
    }

    private static void closeQuietly(Consumer consumer) {
        try {
            consumer.close();
        } catch (RuntimeException e) {
            log.atWarn().setMessage("closing lingering consumer failed")
                    .addKeyValue("consumer", consumer.identifier())
                    .setCause(e)
                    .log();
        }
    }

    public Map<String, Pool> pools() {
        return Map.copyOf(pools);
    }

    /// Routing pools plus every pool still [Pool#drain]ing after removal
    /// (X-11, `docs/spec/router-completion.md` §2 ruling 6) — what the
    /// blocked-groups and group-flush monitoring surfaces read, so a pool
    /// draining its last buffered group stays visible until it finishes.
    /// [#pools] stays the routing-only view `/monitoring/pools` uses.
    public Map<String, Pool> allPools() {
        Map<String, Pool> all = new LinkedHashMap<>(pools);
        drainingPools.forEach(all::putIfAbsent);
        return Map.copyOf(all);
    }

    /// Closes and forgets every draining pool that has finished emptying
    /// (X-11, `docs/spec/router-completion.md` §2 ruling 6) — housekeeping,
    /// alongside [#retireLingeringConsumers].
    ///
    /// @return how many were closed
    public int closeDrainedPools() {
        var closed = 0;
        for (var entry : List.copyOf(drainingPools.entrySet())) {
            if (entry.getValue().drained() && drainingPools.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close();
                closed++;
            }
        }
        return closed;
    }

    /// The queues currently registered, so a caller can start or stop a loop
    /// per queue without holding its own copy of the registry.
    public Set<String> consumerNames() {
        return Set.copyOf(consumers.keySet());
    }

    /// Resolves a [Publisher] for manual/test message injection (§9.1
    /// `POST /messages`, `POST /api/seed/messages`; §5 #61): a queue named
    /// `key` if one is registered, else the alphabetically-first registered
    /// queue — deterministic, matching Go's `Manager.queueForPublish`. The
    /// SAME object a poll loop reads from, not a second connection to the
    /// broker (Go's doc: "reuses the same broker the consumer reads from").
    ///
    /// Empty when no queue is registered at all, or when the resolved
    /// queue's backend does not implement [Publisher] (SQS, NATS — no
    /// publisher wired for those backends today) — both are the caller's
    /// 503 "no publisher" case (§9.1 "Provider-absent degradation").
    public Optional<Publisher> publisher(String key) {
        var names = consumers.keySet();
        String queueName = names.contains(key) ? key : names.stream().sorted().findFirst().orElse(null);
        if (queueName == null) {
            return Optional.empty();
        }
        return consumers.get(queueName) instanceof Publisher publisher ? Optional.of(publisher) : Optional.empty();
    }

    /// One metrics source per registered queue, keyed by [Consumer#identifier]
    /// — §7.1's "key for ... metrics, Prometheus label" — for
    /// [io.flowcatalyst.router.lifecycle.BrokerStatsCache#refresh] and, from
    /// there, `GET /monitoring/queues`' `queue_identifier` and the
    /// Prometheus `queue`/`consumer` labels ([RouterPrometheusCollector]).
    /// Keying by the config queue name here would silently fail to resolve
    /// for NATS (§7.4: identifier is `<stream>/<consumer>`, not the queue
    /// name) exactly as ack/nack resolution did before this index existed.
    ///
    /// Resolved lazily per queue rather than captured: a reconfigure replaces
    /// a consumer without renaming its queue, and a map of bound consumers
    /// would go on sampling the closed one. It also has two callers now — the
    /// housekeeping tick and `POST /monitoring/broker-stats/refresh` — and the
    /// endpoint must sample the same queues the loop does, not a second list
    /// that can drift from it.
    public Map<String, Supplier<Optional<QueueMetrics>>> queueMetricSources() {
        return consumers.values().stream().map(Consumer::identifier).distinct().collect(Collectors.toMap(
                identifier -> identifier,
                identifier -> () -> consumer(identifier).flatMap(Consumer::metrics)));
    }

    /// Drops every consumer. Used on a leadership loss, where the pools and
    /// tracker stay and only the sources go.
    ///
    /// Closes them on the way out even though the only caller has already
    /// done so. `close()` is idempotent on all three backends, and depending
    /// on a caller to have closed first is a precondition invisible from the
    /// call site — exactly the shape of the pool-lifecycle bug this codebase
    /// already shipped once. Cheaper to be self-sufficient.
    /// Terminal: closes every pool this manager owns — routing and draining
    /// alike — and every consumer, active or lingering. Only the process-exit
    /// path calls this; a leadership loss uses [#forgetConsumers] and keeps
    /// the pools, because leadership can come back.
    ///
    /// Without this, a pool's worker executor outlived the manager that
    /// created it: every worker parked on a permit or in a backoff stayed
    /// parked for the life of the JVM. Harmless at process exit, but a test
    /// that builds a manager per case leaked a set of virtual threads per
    /// case, and a router that could be started and stopped inside one JVM
    /// (fcdev, tests) accumulated them.
    @Override
    public void close() {
        forgetConsumers();
        List.copyOf(pools.values()).forEach(Pool::close);
        pools.clear();
        List.copyOf(drainingPools.values()).forEach(Pool::close);
        drainingPools.clear();
    }

    public void forgetConsumers() {
        consumers.values().forEach(RouterManager::closeQuietly);
        consumers.clear();
        consumersByIdentifier.clear();
        deferralLedgers.clear();
        queueConfigs.clear();
        // Lingering consumers too: nothing is going to poll on their behalf
        // any more once leadership is gone, so there is no reason left to
        // wait for the tracker — the process-wide forget subsumes the
        // per-queue retirement [#retireLingeringConsumers] would eventually
        // have done.
        lingeringConsumers.values().stream().flatMap(Collection::stream)
                .forEach(l -> closeQuietly(l.consumer()));
        lingeringConsumers.clear();
    }

    /// Routes one polled batch, in the order the queue delivered it.
    ///
    /// Order matters here and only here: submission order is what a pool's
    /// per-group FIFO preserves. What a pool then does with the messages —
    /// concurrently for IMMEDIATE, in turn for an ordered group — is its own
    /// business.
    ///
    /// @return the pool codes this batch was actually submitted to — what a
    ///         consumer must remember it fed, so it can judge its own
    ///         capacity against those pools rather than the whole process
    ///         (`docs/spec/router.md` §2.4, §6)
    public Set<String> route(List<QueuedMessage> batch, Consumer source) {
        var batchId = Long.toString(batchCounter.incrementAndGet());
        Set<String> fed = new LinkedHashSet<>();
        for (var message : batch) {
            routeOne(message, batchId, source, fed);
        }
        return Set.copyOf(fed);
    }

    private void routeOne(QueuedMessage message, String batchId, Consumer source, Set<String> fed) {
        switch (tracker.register(inFlight(message, batchId, clock.instant()))) {
            case InFlightTracker.Registration.Redelivery ignored -> {
                // The owner's handle has been swapped to this fresher one, so
                // this copy is finished with. Deliberately NOT acked: the
                // owner is still working on the message, and acking here
                // would delete the delivery out from under it.
                log.debug("redelivery of {} dropped; owner keeps the pipeline", message.id());
                // §2.1: if the owned copy is buffered in an ordered group
                // whose drainer has died (its originating consumer was torn
                // down), this redelivery must kick the group back to life
                // rather than leave it stalled until the platform notices.
                if (!message.group().isEmpty()) {
                    poolFor(message).ifPresent(pool -> pool.resumeGroup(message.group()));
                }
            }
            case InFlightTracker.Registration.ExternalRequeue ignored -> {
                // A second, distinct delivery of a message we already own.
                // ACK it on its OWN handle so the duplicate leaves the broker,
                // and do not deliver it.
                log.debug("external requeue of {} acked away", message.id());
                source.ack(message);
            }
            case InFlightTracker.Registration.New ignored -> {
                if (strictRouting) {
                    var reason = malformedReason(message.message());
                    if (reason != null) {
                        tracker.remove(message.id());
                        source.ack(message);
                        warnings.raise(Warnings.Severity.WARNING, "CONFIGURATION",
                                "message " + message.id() + " is malformed (" + reason
                                        + "); acked without delivery");
                        return;
                    }
                }
                submit(message, fed);
            }
        }
    }

    /// R-13/R-16, `docs/spec/router.md` §2.3: the strict-gate malformed
    /// reasons, or `null` when the message is well-formed. Checked only
    /// under [#strictRouting] — off, the pre-ruling fallbacks in [Message]
    /// and [#poolFor] apply instead and this is never called.
    private static String malformedReason(Message message) {
        if (message.poolCode() == null || message.poolCode().isBlank()) {
            return "poolCode is absent";
        }
        if (!message.dispatchModeSpecified()) {
            return "dispatchMode is absent";
        }
        if (message.dispatchMode().requiresOrdering() && message.groupId().isEmpty()) {
            return "dispatchMode " + message.dispatchMode() + " has no messageGroupId";
        }
        return null;
    }

    private void submit(QueuedMessage message, Set<String> fed) {
        var pool = poolFor(message);
        if (pool.isEmpty()) {
            // Before the first reconfigure, or after shutdown. The message is
            // untouched, so hand it back rather than holding it.
            tracker.remove(message.id());
            var consumer = consumers.get(message.queueId());
            if (consumer != null) {
                consumer.nack(message, NO_POOL_NACK_DELAY);
            }
            return;
        }
        var code = pool.get().config().code();
        lastRoutedAt.put(code, clock.instant());
        fed.add(code);
        pool.get().submit(message);
    }

    /// Resolves the pool a message runs in.
    ///
    /// Three cases, and the middle one is the reason this is not a map lookup:
    ///
    /// 1. A known code → that pool.
    /// 2. An unknown code ending `-DEFAULT-POOL` → **synthesised on demand**.
    ///    These are the per-client fallback pools; the external config service
    ///    does not know about them, so treating them as unknown would send
    ///    every client's unpooled traffic to one shared pool and raise a
    ///    warning per message while doing it.
    /// 3. Any other unknown code, or none → `DEFAULT-POOL`, with a warning
    ///    when a code was actually named. An empty code is not a mistake, so
    ///    it is not warned about.
    Optional<Pool> poolFor(QueuedMessage message) {
        var code = message.message().poolCode();
        if (code != null && !code.isEmpty()) {
            var pool = pools.get(code);
            if (pool != null) {
                return Optional.of(pool);
            }
            if (code.endsWith(DEFAULT_POOL_SUFFIX)) {
                return Optional.of(pools.computeIfAbsent(code, synthesised -> {
                    // computeIfAbsent guarantees this lambda runs at most once
                    // per code, so wiring the listener and signalling the gate
                    // here — rather than unconditionally after the call —
                    // fires exactly once, on the actual creation.
                    var created = poolFactory.create(new Pool.Config(synthesised, DEFAULT_POOL_CONCURRENCY, 0));
                    created.onCapacityFreed(capacityGate::signal);
                    created.onDeferral(this::noteDeferral);
                    capacityGate.signal();
                    return created;
                }));
            }
            warnings.raise(Warnings.Severity.WARNING, "ROUTING",
                    "no pool for pool_code \"" + code + "\"; routed to " + DEFAULT_POOL);
        }
        return Optional.ofNullable(pools.get(DEFAULT_POOL));
    }

    /// Whether any pool has room. When none does, the poll loops pause rather
    /// than pulling messages they would only have to hand straight back.
    public boolean anyPoolHasCapacity() {
        return pools.values().stream().anyMatch(RouterManager::hasCapacity);
    }

    /// Whether **at least one** of the named pools currently has room.
    ///
    /// The per-consumer half of §2.4/§6: a consumer pauses only when *every*
    /// pool its own last batch fed is at capacity, so a caller checks this
    /// against exactly those pools rather than the whole process — one full
    /// pool elsewhere in the router must not pause a consumer that is not
    /// feeding it. A pool code this manager no longer knows (removed by a
    /// reconfigure) is simply absent from the match; a caller whose entire
    /// remembered set has gone stale that way should fall back to
    /// [#anyPoolHasCapacity()] rather than call this with nothing left to
    /// check.
    public boolean poolsHaveCapacity(Set<String> poolCodes) {
        return pools.entrySet().stream()
                .filter(entry -> poolCodes.contains(entry.getKey()))
                .anyMatch(entry -> hasCapacity(entry.getValue()));
    }

    private static boolean hasCapacity(Pool pool) {
        return pool.queueSize() < pool.config().queueCapacity();
    }

    /// Applies a new configuration to the running router.
    ///
    /// Pools and consumers are handled differently on purpose. A pool can be
    /// **adjusted in place** — concurrency and rate limit are hot — so an
    /// existing pool keeps its buffered work and its in-flight deliveries. A
    /// consumer cannot: its identity is bound to a broker connection, so any
    /// change means stop and rebuild, which aborts that queue's in-flight
    /// deliveries and parks its ordered groups until redelivery resumes them.
    /// That asymmetry is why a pool edit is cheap and a queue edit is not.
    ///
    /// A consumer that cannot be built does **not** abort the rest: the Go
    /// aborts mid-way, leaving earlier changes applied and later queues
    /// unstarted (§13 Q35, unruled). Here every queue is attempted and the
    /// failures are reported, so a single bad URI cannot silently halve the
    /// router. This is a deliberate deviation and the result records it.
    ///
    /// @return what changed, for the caller to log or surface
    public ReconfigureResult reconfigure(RouterConfig config, ConsumerFactory consumerFactory) {
        var wantedPools = wantedPools(config);
        var removedPools = applyPools(wantedPools);
        var consumerChanges = applyConsumers(config, consumerFactory);
        return new ReconfigureResult(
                wantedPools.size(), removedPools, consumerChanges.started(), consumerChanges.stopped(),
                consumerChanges.failed(), consumerChanges.replaced());
    }

    /// What a reconfigure did.
    ///
    /// @param failedQueues   queues that could not be built. Non-empty means
    ///                       the router is running with less than its
    ///                       configuration asks for, which is worth surfacing
    ///                       rather than leaving in a log line.
    /// @param replacedQueues queue names whose consumer was rebuilt under the
    ///                       same name — a config change, not a removal
    ///                       (`docs/spec/router-completion.md` §2 ruling 5).
    ///                       [RouterServer#syncLoops] must restart exactly
    ///                       these loops even though their name never left
    ///                       [#consumerNames]: the identity behind the name
    ///                       changed, and the old loop is otherwise left
    ///                       polling a consumer nothing else references any
    ///                       more.
    public record ReconfigureResult(int pools, int poolsRemoved, int consumersStarted,
                                    int consumersStopped, List<String> failedQueues, List<String> replacedQueues) {

        public ReconfigureResult {
            failedQueues = List.copyOf(failedQueues);
            replacedQueues = List.copyOf(replacedQueues);
        }

        public boolean complete() {
            return failedQueues.isEmpty();
        }
    }

    /// The pool set the configuration asks for, plus the global fallback.
    ///
    /// `DEFAULT-POOL` is always present: [#poolFor] falls back to it, and a
    /// configuration that omits it would leave messages with nowhere to go.
    private Map<String, PoolSpec> wantedPools(RouterConfig config) {
        Map<String, PoolSpec> wanted = new LinkedHashMap<>();
        config.processingPools().forEach(pool -> wanted.put(pool.code(), pool));
        wanted.computeIfAbsent(DEFAULT_POOL,
                code -> new PoolSpec(code, DEFAULT_POOL_CONCURRENCY, 0));
        return wanted;
    }

    private int applyPools(Map<String, PoolSpec> wanted) {
        var removed = 0;
        for (var code : List.copyOf(pools.keySet())) {
            if (!wanted.containsKey(code) && !code.endsWith(DEFAULT_POOL_SUFFIX)) {
                // Synthesised per-client fallbacks are never in the config and
                // must survive a reconfigure that does not mention them.
                var pool = pools.remove(code);
                if (pool != null) {
                    // drain, not close (X-11, `docs/spec/router-completion.md`
                    // §2 ruling 6): stop admitting at once — this pool has
                    // already left [#pools], so [#poolFor] can never route to
                    // it again — but let its existing buffer and workers
                    // finish in the background. [#closeDrainedPools] is the
                    // housekeeping follow-up that actually releases the
                    // worker executor once nothing is left. A reconfigure
                    // must never block on that.
                    pool.drain();
                    drainingPools.put(code, pool);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            // A pool leaving [#pools] can turn a consumer's remembered
            // fed-pool set stale (`docs/spec/router.md` §2.4/§6): its
            // [ConsumerLoop#hasRoom] falls back to
            // [#anyPoolHasCapacity]/[#poolsHaveCapacity], which must be
            // re-evaluated rather than left parked on a set that no longer
            // exists.
            capacityGate.signal();
        }
        wanted.forEach((code, config) -> {
            var existing = pools.get(code);
            if (existing == null) {
                addPool(code, poolFactory.create(config.toRuntime()));
                return;
            }
            // Rate limit is always reapplied; concurrency only when the
            // configuration actually states one, so a zero does not silently
            // shrink a running pool.
            //
            // Belt and braces: Pool.updateConcurrency rejects a non-positive
            // value too, so removing this check changes nothing observable.
            // It stays because the *intent* belongs at the call site — a
            // reader here should not have to know how the pool defends
            // itself to see that an unstated concurrency is left alone.
            existing.updateRateLimit(config.rateLimitPerMinute());
            if (config.statesConcurrency()) {
                existing.updateConcurrency(config.concurrency());
            }
        });
        // R-59: recorded so eviction never removes an explicitly configured
        // pool just because its code happens to end in the synthesised
        // suffix — the synthesis mechanism must never override a real
        // config entry.
        configuredPoolCodes = Set.copyOf(wanted.keySet());
        return removed;
    }

    /// Evicts every synthesised `{client}-DEFAULT-POOL` (R-59,
    /// `docs/spec/router.md` §2.2) idle past `idleTtl` — no message routed to
    /// it in that time — **and** currently holding no work: a pool still
    /// finishing buffered messages is skipped until a later tick finds it
    /// truly empty, per the drain rules (§5). An explicitly configured pool
    /// of the same code is never touched, whatever its idle time.
    ///
    /// Removed from routing and closed; a later message naming the same code
    /// synthesises it again from scratch, exactly as if it had never
    /// existed.
    ///
    /// @return how many pools were evicted
    public int evictIdleSynthesisedPools(Duration idleTtl) {
        var now = clock.instant();
        var evicted = 0;
        for (var code : List.copyOf(pools.keySet())) {
            if (!code.endsWith(DEFAULT_POOL_SUFFIX) || configuredPoolCodes.contains(code)) {
                continue;
            }
            var lastRouted = lastRoutedAt.get(code);
            if (lastRouted == null || Duration.between(lastRouted, now).compareTo(idleTtl) < 0) {
                continue;
            }
            var pool = pools.get(code);
            if (pool == null || pool.queueSize() != 0 || pool.activeWorkers() != 0) {
                // Still holding work: the group processors finish their
                // buffers first (§5); a later tick reconsiders it.
                continue;
            }
            if (pools.remove(code, pool)) {
                lastRoutedAt.remove(code);
                pool.close();
                evicted++;
            }
        }
        if (evicted > 0) {
            capacityGate.signal();
        }
        return evicted;
    }

    private ConsumerChanges applyConsumers(RouterConfig config, ConsumerFactory factory) {
        Map<String, QueueConfig> wanted = new LinkedHashMap<>();
        config.queues().forEach(queue -> wanted.put(queue.queueName(), queue));
        // A missing-streak is forgotten once the config stops naming the
        // queue (owner ruling 2026-09-11) — see #missingQueues.
        missingQueues.retainAll(wanted.keySet());

        var stopped = 0;
        var replaced = new ArrayList<String>();
        for (var entry : List.copyOf(queueConfigs.entrySet())) {
            // Not wanted at all, or wanted differently — either way the
            // running consumer is not the one we should have, so it goes.
            // A queue the config dropped compares against null, which is
            // "different" by the same rule as any other change.
            var newConfig = wanted.get(entry.getKey());
            if (!entry.getValue().sameConsumerTopology(newConfig)) {
                stopConsumer(entry.getKey());
                stopped++;
                if (newConfig != null) {
                    // Still wanted, under the same name, just different — a
                    // CHANGE, not a removal. The consumer built below for it
                    // is a REPLACEMENT the caller must swap a running loop
                    // onto, not a queue starting from cold.
                    replaced.add(entry.getKey());
                }
            }
        }

        // Build concurrently: each one opens a broker connection, so in turn
        // a deployment waits for the SUM of its brokers' latencies rather
        // than the slowest, and one unreachable broker delays every queue
        // behind it. Bounded, so a broker that never answers cannot hold a
        // reconfigure — or a leadership transition — open indefinitely.
        var toBuild = wanted.entrySet().stream()
                .filter(entry -> !consumers.containsKey(entry.getKey()))
                .toList();
        var failed = new ConcurrentLinkedQueue<String>();
        var started = new AtomicInteger();
        Concurrently.forEach(toBuild, entry -> {
            switch (factory.create(entry.getValue())) {
                case ConsumerBuild.Built built -> {
                    var consumer = built.consumer();
                    consumers.put(entry.getKey(), consumer);
                    consumersByIdentifier.put(consumer.identifier(), consumer);
                    deferralLedgers.putIfAbsent(consumer.identifier(), new DeferralLedger());
                    queueConfigs.put(entry.getKey(), entry.getValue());
                    // Built after having been missing: the streak is over,
                    // so the NEXT disappearance logs its own INFO rather than
                    // finding the queue already marked missing.
                    missingQueues.remove(entry.getKey());
                    started.incrementAndGet();
                }
                case ConsumerBuild.Failed ignored -> failed.add(entry.getKey());
                case ConsumerBuild.Missing ignored -> {
                    // Not a failure (owner ruling 2026-09-11): no consumer,
                    // no warning — one INFO per missing streak, keyed by the
                    // config queue name so a recheck that still finds it
                    // missing stays silent.
                    if (missingQueues.add(entry.getKey())) {
                        log.atInfo().setMessage(MISSING_QUEUE_MESSAGE)
                                .addKeyValue("queue", entry.getKey())
                                .log();
                    }
                }
            }
        }, CONSUMER_BUILD_TIMEOUT, "consumer build");

        // Stable order regardless of which finished first, so the same
        // failure reads the same way twice.
        var failedNames = failed.stream().sorted().toList();
        // A replaced queue that failed to rebuild is not actually replaced —
        // it is simply gone, same as any other build failure — so it must
        // not be reported twice under two different meanings.
        var replacedNames = replaced.stream().filter(name -> !failedNames.contains(name)).sorted().toList();
        return new ConsumerChanges(started.get(), stopped, failedNames, replacedNames);
    }

    /// Detaches (never closes) the active consumer for `queueName` — the
    /// pre-ruling behaviour closed it here, which is exactly the X-11/R-26
    /// defect this method now exists to not repeat: an in-flight delivery or
    /// a buffered message still referencing it would find its consumer gone.
    /// [#retireLingeringConsumers] is what actually closes it, once nothing
    /// does any more.
    private void stopConsumer(String queueName) {
        queueConfigs.remove(queueName);
        var consumer = consumers.remove(queueName);
        if (consumer != null) {
            consumersByIdentifier.remove(consumer.identifier(), consumer);
            // Nothing will poll this identifier again, so its ledger goes
            // too — a deferral still landing for it after this point is the
            // race [#noteDeferral]'s "dropped without error" is for.
            deferralLedgers.remove(consumer.identifier());
            linger(queueName, consumer);
        }
    }

    /// [#stopConsumer]'s treatment, driven bottom-up: [ConsumerLoop] calls
    /// this when a poll answers [Consumer.PollResult.QueueMissing] — the
    /// queue backing `identifier` has disappeared (owner ruling 2026-09-11,
    /// `docs/spec/router.md` §7.2) — rather than top-down by a reconfigure.
    /// Detaches to the lingering set exactly as [#stopConsumer] does (never
    /// closes: an in-flight delivery it still holds must still be able to
    /// ack/nack), which also drops it from [#queueConfigs] so the next
    /// config apply's `toBuild` set includes it again and rechecks the
    /// queue.
    ///
    /// A no-op if `identifier` no longer resolves to a currently active
    /// consumer — a race with a reconfigure that already replaced or removed
    /// it first.
    void detachMissingConsumer(String identifier) {
        consumers.entrySet().stream()
                .filter(entry -> entry.getValue().identifier().equals(identifier))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(this::stopConsumer);
    }

    private record ConsumerChanges(int started, int stopped, List<String> failed, List<String> replaced) {
    }

    /// Builds the tracker entry for `message`, shared verbatim with
    /// [QueueBroker] (unit 3, `docs/spec/router-completion.md`): the
    /// process-time backstop ([Broker#owns]) re-registers ownership with the
    /// same shape route-time registration used, and any drift between two
    /// hand-written builders is exactly the kind of thing that quietly
    /// breaks the fields the tracker actually compares (poolCode, group).
    ///
    /// @param batchId the poll batch this entry belongs to. [QueueBroker]
    ///                has no batch context at delivery time — it is
    ///                re-registering ownership, not routing — so it passes a
    ///                fixed marker; the field is monitoring metadata only
    ///                and plays no part in [InFlightTracker]'s own decisions.
    static InFlightMessage inFlight(QueuedMessage message, String batchId, Instant now) {
        return new InFlightMessage(
                message.id(),
                message.brokerMessageId(),
                // Unresolved on purpose: an operator should see that the
                // message named no pool, not the fallback it landed in.
                message.message().poolCode() == null ? "" : message.message().poolCode(),
                message.queueId(),
                now,
                now,
                message.group(),
                batchId,
                message.receiptHandle(),
                message.attempts());
    }
}

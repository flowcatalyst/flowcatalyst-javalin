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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Decides where each polled message goes, and hands it to a pool.
///
/// Everything upstream of this is a queue; everything downstream is a pool.
/// The manager owns the two things that sit between them: **ownership** (via
/// the in-flight tracker, which decides whether an arriving copy is new, a
/// redelivery, or a duplicate from elsewhere) and **pool resolution**
/// (`docs/spec/router.md` §3.3).
public final class RouterManager {

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

    /// How long a reconfigure will spend building consumers before carrying
    /// on without the stragglers. They are reported as failures and retried
    /// on the next reconfigure.
    static final Duration CONSUMER_BUILD_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, Pool> pools = new ConcurrentHashMap<>();
    private final Map<String, Consumer> consumers = new ConcurrentHashMap<>();

    /// The configuration each running consumer was built from, so a change
    /// can be detected without asking the consumer to describe itself.
    private final Map<String, QueueConfig> queueConfigs = new ConcurrentHashMap<>();
    private final InFlightTracker tracker;
    private final Warnings warnings;
    private final Clock clock;
    private final PoolFactory poolFactory;
    private final AtomicLong batchCounter = new AtomicLong();

    /// Concurrency given to a pool the configuration named without one
    /// (spec constant 2).
    public static final int DEFAULT_POOL_CONCURRENCY = 20;

    /// Builds a pool from its configuration.
    @FunctionalInterface
    public interface PoolFactory {
        Pool create(Pool.Config config);
    }

    /// Builds a consumer for a configured queue. Returning empty means the
    /// queue could not be built — an unknown URI scheme, an unreachable
    /// broker — and the reconfigure carries on with the rest.
    @FunctionalInterface
    public interface ConsumerFactory {
        Optional<Consumer> create(QueueConfig config);
    }

    public RouterManager(InFlightTracker tracker, Warnings warnings, Clock clock, PoolFactory poolFactory) {
        this.tracker = tracker;
        this.warnings = warnings;
        this.clock = clock;
        this.poolFactory = poolFactory;
    }

    public void registerPool(String code, Pool pool) {
        pools.put(code, pool);
    }

    public void registerConsumer(Consumer consumer) {
        consumers.put(consumer.identifier(), consumer);
    }

    public Optional<Consumer> consumer(String queueId) {
        return Optional.ofNullable(consumers.get(queueId));
    }

    public Map<String, Pool> pools() {
        return Map.copyOf(pools);
    }

    /// The queues currently registered, so a caller can start or stop a loop
    /// per queue without holding its own copy of the registry.
    public java.util.Set<String> consumerNames() {
        return java.util.Set.copyOf(consumers.keySet());
    }

    /// Drops every consumer. Used on a leadership loss, where the pools and
    /// tracker stay and only the sources go.
    ///
    /// Closes them on the way out even though the only caller has already
    /// done so. `close()` is idempotent on all three backends, and depending
    /// on a caller to have closed first is a precondition invisible from the
    /// call site — exactly the shape of the pool-lifecycle bug this codebase
    /// already shipped once. Cheaper to be self-sufficient.
    public void forgetConsumers() {
        consumers.values().forEach(consumer -> {
            try {
                consumer.close();
            } catch (RuntimeException e) {
                log.warn("closing consumer {} failed", consumer.identifier(), e);
            }
        });
        consumers.clear();
        queueConfigs.clear();
    }

    /// Routes one polled batch, in the order the queue delivered it.
    ///
    /// Order matters here and only here: submission order is what a pool's
    /// per-group FIFO preserves. What a pool then does with the messages —
    /// concurrently for IMMEDIATE, in turn for an ordered group — is its own
    /// business.
    public void route(List<QueuedMessage> batch, Consumer source) {
        var batchId = Long.toString(batchCounter.incrementAndGet());
        for (var message : batch) {
            routeOne(message, batchId, source);
        }
    }

    private void routeOne(QueuedMessage message, String batchId, Consumer source) {
        switch (tracker.register(inFlight(message, batchId))) {
            case InFlightTracker.Registration.Redelivery ignored -> {
                // The owner's handle has been swapped to this fresher one, so
                // this copy is finished with. Deliberately NOT acked: the
                // owner is still working on the message, and acking here
                // would delete the delivery out from under it.
                log.debug("redelivery of {} dropped; owner keeps the pipeline", message.id());
            }
            case InFlightTracker.Registration.ExternalRequeue ignored -> {
                // A second, distinct delivery of a message we already own.
                // ACK it on its OWN handle so the duplicate leaves the broker,
                // and do not deliver it.
                log.debug("external requeue of {} acked away", message.id());
                source.ack(message);
            }
            case InFlightTracker.Registration.New ignored -> submit(message);
        }
    }

    private void submit(QueuedMessage message) {
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
                return Optional.of(pools.computeIfAbsent(code,
                        synthesised -> poolFactory.create(new Pool.Config(synthesised, DEFAULT_POOL_CONCURRENCY, 0))));
            }
            warnings.raise(Warnings.Severity.WARNING, "ROUTING",
                    "no pool for pool_code \"" + code + "\"; routed to " + DEFAULT_POOL);
        }
        return Optional.ofNullable(pools.get(DEFAULT_POOL));
    }

    /// Whether any pool has room. When none does, the poll loops pause rather
    /// than pulling messages they would only have to hand straight back.
    public boolean anyPoolHasCapacity() {
        return pools.values().stream().anyMatch(pool -> pool.queueSize() < pool.config().queueCapacity());
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
                consumerChanges.failed());
    }

    /// What a reconfigure did.
    ///
    /// @param failedQueues queues that could not be built. Non-empty means the
    ///                     router is running with less than its configuration
    ///                     asks for, which is worth surfacing rather than
    ///                     leaving in a log line.
    public record ReconfigureResult(int pools, int poolsRemoved, int consumersStarted,
                                    int consumersStopped, List<String> failedQueues) {

        public ReconfigureResult {
            failedQueues = List.copyOf(failedQueues);
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
        Map<String, PoolSpec> wanted = new java.util.LinkedHashMap<>();
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
                    // close, not stop: this pool is being discarded, so its
                    // worker executor goes with it. Stopping alone would hand
                    // back the buffers and leak the threads.
                    pool.close();
                    removed++;
                }
            }
        }
        wanted.forEach((code, config) -> {
            var existing = pools.get(code);
            if (existing == null) {
                pools.put(code, poolFactory.create(config.toRuntime()));
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
        return removed;
    }

    private ConsumerChanges applyConsumers(RouterConfig config, ConsumerFactory factory) {
        Map<String, QueueConfig> wanted = new java.util.LinkedHashMap<>();
        config.queues().forEach(queue -> wanted.put(queue.queueName(), queue));

        var stopped = 0;
        for (var entry : List.copyOf(queueConfigs.entrySet())) {
            // Not wanted at all, or wanted differently — either way the
            // running consumer is not the one we should have, so it goes.
            // A queue the config dropped compares against null, which is
            // "different" by the same rule as any other change.
            if (!entry.getValue().sameConsumerTopology(wanted.get(entry.getKey()))) {
                stopConsumer(entry.getKey());
                stopped++;
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
        var failed = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var started = new java.util.concurrent.atomic.AtomicInteger();
        Concurrently.forEach(toBuild, entry -> {
            var built = factory.create(entry.getValue());
            if (built.isEmpty()) {
                failed.add(entry.getKey());
                return;
            }
            consumers.put(entry.getKey(), built.get());
            queueConfigs.put(entry.getKey(), entry.getValue());
            started.incrementAndGet();
        }, CONSUMER_BUILD_TIMEOUT, "consumer build");

        // Stable order regardless of which finished first, so the same
        // failure reads the same way twice.
        var failedNames = failed.stream().sorted().toList();
        return new ConsumerChanges(started.get(), stopped, failedNames);
    }

    private void stopConsumer(String queueName) {
        queueConfigs.remove(queueName);
        var consumer = consumers.remove(queueName);
        if (consumer != null) {
            consumer.close();
        }
    }

    private record ConsumerChanges(int started, int stopped, List<String> failed) {
    }

    private InFlightMessage inFlight(QueuedMessage message, String batchId) {
        var now = clock.instant();
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

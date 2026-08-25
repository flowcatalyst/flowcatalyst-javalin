package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.QueuedMessage;
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

    private final Map<String, Pool> pools = new ConcurrentHashMap<>();
    private final Map<String, Consumer> consumers = new ConcurrentHashMap<>();
    private final InFlightTracker tracker;
    private final Warnings warnings;
    private final Clock clock;
    private final PoolFactory poolFactory;
    private final AtomicLong batchCounter = new AtomicLong();

    /// Builds a pool for a code the configuration did not define — used only
    /// for the per-client fallback pools.
    @FunctionalInterface
    public interface PoolFactory {
        Pool create(String poolCode);
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
                return Optional.of(pools.computeIfAbsent(code, poolFactory::create));
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

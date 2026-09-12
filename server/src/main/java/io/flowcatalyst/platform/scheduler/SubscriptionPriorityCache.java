package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.db.generated.tables.MsgSubscriptions;
import io.flowcatalyst.platform.shared.dispatch.QueuePriority;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;

/// Caches `msg_subscriptions.queue` — the raw stored dispatch-priority
/// column (ruling R1/R6, `docs/go-mirror/2026-09-12-dispatch-rulings.md`) —
/// keyed by subscription id, so [SqsDispatchPublisher] can resolve a claimed
/// job's priority without a second query per publish: a claimed
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow]
/// carries only `subscriptionId`, and the claim query is deliberately
/// join-free (`FOR UPDATE SKIP LOCKED` over one table).
///
/// Refreshed on the same TTL as [PoolCodeResolver] and [PausedConnectionCache]
/// — all three cache a slow-moving configuration read on every publish, and a
/// stale read costs at most one TTL of misrouted priority, never a locked
/// join.
///
/// [#priorityFor] never throws (R6): a job with no `subscriptionId`, a
/// `subscriptionId` this cache has no row for (deleted subscription, or a
/// cache miss the TTL hasn't caught up to), a `NULL` stored value, or
/// unrecognised legacy text (`workers-high`) all resolve to
/// [QueuePriority#DEFAULT] via [QueuePriority#forPublishing] — the read
/// counterpart of the write-side [QueuePriority#parse] validation, which
/// cannot reach rows that already exist.
public final class SubscriptionPriorityCache {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionPriorityCache.class);

    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private static final MsgSubscriptions SUB = MSG_SUBSCRIPTIONS;

    private final DSLContext dsl;
    private final Duration ttl;

    /// Guards [#refresh]; readers never block on it ([#snapshot] is volatile).
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private record Snapshot(Map<String, String> storedQueueBySubscriptionId, Instant refreshedAt) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Instant.EPOCH);
    }

    public SubscriptionPriorityCache(DataSource dataSource) {
        this(dataSource, DEFAULT_TTL);
    }

    SubscriptionPriorityCache(DataSource dataSource, Duration ttl) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /// The priority a job raised from `subscriptionId` publishes at. Never
    /// `null`, never throws — see the class doc for every fallback case.
    public QueuePriority priorityFor(String subscriptionId) {
        if (subscriptionId == null) {
            return QueuePriority.DEFAULT;
        }
        ensureFresh();
        return QueuePriority.forPublishing(snapshot.storedQueueBySubscriptionId().get(subscriptionId));
    }

    private void ensureFresh() {
        if (Duration.between(snapshot.refreshedAt(), Instant.now()).compareTo(ttl) < 0) return;
        synchronized (refreshLock) {
            if (Duration.between(snapshot.refreshedAt(), Instant.now()).compareTo(ttl) < 0) return;
            try {
                refresh();
            } catch (RuntimeException e) {
                LOG.warn("subscription priority cache refresh failed; resolving from the stale cache", e);
            }
        }
    }

    private void refresh() {
        Map<String, String> stored = dsl.select(SUB.ID, SUB.QUEUE).from(SUB).fetchMap(SUB.ID, SUB.QUEUE);
        snapshot = new Snapshot(stored, Instant.now());
        LOG.debug("subscription priority cache refreshed subscriptions={}", stored.size());
    }
}

package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.db.generated.tables.MsgConnections;
import io.flowcatalyst.db.generated.tables.MsgSubscriptions;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import static io.flowcatalyst.db.generated.Tables.MSG_CONNECTIONS;
import static io.flowcatalyst.db.generated.Tables.MSG_SUBSCRIPTIONS;

/// Caches the set of subscription ids whose target connection is `PAUSED`
/// (dispatch-seam spec §3, step 3). `PendingJobPoller` skips a claimed job
/// whose subscription is in this set — the row stays `PENDING` until the
/// connection is reactivated, no different from any other claim-time
/// hold-back.
///
/// Refreshed on the same TTL as [PoolCodeResolver] — both cache a
/// slow-moving configuration read on every poll tick, and a stale read costs
/// at most one TTL of misrouting.
public final class PausedConnectionCache {

    private static final Logger LOG = LoggerFactory.getLogger(PausedConnectionCache.class);

    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private static final MsgSubscriptions SUB = MSG_SUBSCRIPTIONS;
    private static final MsgConnections CONN = MSG_CONNECTIONS;

    private final DSLContext dsl;
    private final Duration ttl;

    /// Guards [#refresh]; readers never block on it ([#snapshot] is volatile).
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private record Snapshot(Set<String> pausedSubscriptionIds, Instant refreshedAt) {
        static final Snapshot EMPTY = new Snapshot(Set.of(), Instant.EPOCH);
    }

    public PausedConnectionCache(DataSource dataSource) {
        this(dataSource, DEFAULT_TTL);
    }

    PausedConnectionCache(DataSource dataSource, Duration ttl) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /// The cached set, refreshing first if stale. Never `null`; empty when
    /// nothing is paused.
    public Set<String> pausedSubscriptionIds() {
        ensureFresh();
        return snapshot.pausedSubscriptionIds();
    }

    private void ensureFresh() {
        if (Duration.between(snapshot.refreshedAt(), Instant.now()).compareTo(ttl) < 0) return;
        synchronized (refreshLock) {
            if (Duration.between(snapshot.refreshedAt(), Instant.now()).compareTo(ttl) < 0) return;
            try {
                refresh();
            } catch (RuntimeException e) {
                LOG.warn("paused connection cache refresh failed; resolving from the stale cache", e);
            }
        }
    }

    private void refresh() {
        Set<String> paused = dsl.select(SUB.ID).from(SUB)
                .join(CONN).on(CONN.ID.eq(SUB.CONNECTION_ID))
                .where(CONN.STATUS.eq("PAUSED"))
                .fetchSet(SUB.ID);
        snapshot = new Snapshot(paused, Instant.now());
        LOG.debug("paused connection cache refreshed paused={}", paused.size());
    }
}

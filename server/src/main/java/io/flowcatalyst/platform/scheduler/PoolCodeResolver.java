package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.db.generated.tables.MsgDispatchPools;
import io.flowcatalyst.db.generated.tables.TntClients;
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

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_POOLS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;

/// Composes the `poolCode` a dispatch job publishes (dispatch-seam spec §2
/// "`poolCode` composition", ledger R-16):
///
/// | Job's pool | Job's client | Published `poolCode` |
/// |---|---|---|
/// | set, owned by a client | — | `{clientIdentifier}-{poolCode}` |
/// | set, platform-level | — | `{poolCode}`, no prefix |
/// | unset (or unresolvable) | resolves | `{clientIdentifier}-DEFAULT-POOL` |
/// | unset | unresolvable | `DEFAULT-POOL` |
///
/// Resolved by id lookup, never by `JOIN`ing onto the claim query: the claim
/// runs `FOR UPDATE SKIP LOCKED` over `msg_dispatch_jobs` alone, and pools /
/// clients change almost never, so caching them here — refreshed on the same
/// TTL as [PausedConnectionCache] — costs at most one TTL of stale routing on
/// a resolution failure, never a locked join.
///
/// A resolution failure is never fatal: an unknown pool id (deleted pool)
/// falls through to the client's default pool, an unknown client to the
/// global default — a job always publishes a routable code rather than being
/// dropped. A cache-refresh failure serves the previous snapshot.
public final class PoolCodeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PoolCodeResolver.class);

    /// The router's global fallback pool.
    public static final String DEFAULT_POOL_CODE = "DEFAULT-POOL";

    /// The one permitted structural read of a composed code — see
    /// [#isDefaultPoolCode]. The composed form's two halves may themselves
    /// contain hyphens, so nothing may split a code back apart.
    private static final String DEFAULT_POOL_SUFFIX = "-" + DEFAULT_POOL_CODE;

    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private static final MsgDispatchPools POOL = MSG_DISPATCH_POOLS;
    private static final TntClients CLIENT = TNT_CLIENTS;

    private final DSLContext dsl;
    private final Duration ttl;

    /// Guards [#refresh] so two callers racing a stale cache build only one
    /// new snapshot; readers never block on it ([#snapshot] is volatile).
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    private record PoolRef(String code, String clientIdentifier) {
    }

    private record Snapshot(Map<String, PoolRef> pools, Map<String, String> clientIdentifiers, Instant refreshedAt) {
        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Instant.EPOCH);
    }

    public PoolCodeResolver(DataSource dataSource) {
        this(dataSource, DEFAULT_TTL);
    }

    PoolCodeResolver(DataSource dataSource, Duration ttl) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /// The code to publish for a job carrying `dispatchPoolId` / `clientId`
    /// (either or both may be `null`), per the ruled chain above.
    public String resolve(String dispatchPoolId, String clientId) {
        ensureFresh();
        Snapshot s = snapshot;
        if (dispatchPoolId != null) {
            PoolRef ref = s.pools().get(dispatchPoolId);
            if (ref != null && !ref.code().isEmpty()) {
                return ref.clientIdentifier() == null || ref.clientIdentifier().isEmpty()
                        ? ref.code()
                        : ref.clientIdentifier() + "-" + ref.code();
            }
        }
        if (clientId != null) {
            String identifier = s.clientIdentifiers().get(clientId);
            if (identifier != null && !identifier.isEmpty()) {
                return identifier + DEFAULT_POOL_SUFFIX;
            }
        }
        return DEFAULT_POOL_CODE;
    }

    /// Whether `code` names a fallback pool — the global [#DEFAULT_POOL_CODE]
    /// or any per-client `{identifier}-DEFAULT-POOL`.
    public static boolean isDefaultPoolCode(String code) {
        return DEFAULT_POOL_CODE.equals(code) || (code != null && code.endsWith(DEFAULT_POOL_SUFFIX));
    }

    private void ensureFresh() {
        if (Duration.between(snapshot.refreshedAt(), Instant.now()).compareTo(ttl) < 0) return;
        synchronized (refreshLock) {
            if (Duration.between(snapshot.refreshedAt(), Instant.now()).compareTo(ttl) < 0) return;
            try {
                refresh();
            } catch (RuntimeException e) {
                LOG.warn("pool code cache refresh failed; resolving from the stale cache", e);
            }
        }
    }

    private void refresh() {
        var pools = dsl.select(POOL.ID, POOL.CODE, POOL.CLIENT_IDENTIFIER).from(POOL)
                .fetchMap(POOL.ID, r -> new PoolRef(r.get(POOL.CODE), r.get(POOL.CLIENT_IDENTIFIER)));
        var clients = dsl.select(CLIENT.ID, CLIENT.IDENTIFIER).from(CLIENT)
                .fetchMap(CLIENT.ID, CLIENT.IDENTIFIER);
        snapshot = new Snapshot(pools, clients, Instant.now());
        LOG.debug("pool code cache refreshed pools={} clients={}", pools.size(), clients.size());
    }
}

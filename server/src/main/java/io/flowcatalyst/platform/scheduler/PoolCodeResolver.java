package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.db.generated.tables.MsgDispatchPools;
import io.flowcatalyst.db.generated.tables.TntClients;
import io.flowcatalyst.platform.client.ClientIdentifier;
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
/// "`poolCode` composition", ledger R-16 — **superseded for the two
/// platform-level rows by ruling R7**, `docs/go-mirror/2026-09-12-dispatch-rulings.md`):
///
/// | Job's pool | Job's client | Published `poolCode` |
/// |---|---|---|
/// | set, owned by a client | — | `{clientIdentifier}-{poolCode}` |
/// | set, platform-level | — | `{poolCode}`, no prefix — **superseded (R7): `platform-{poolCode}`** |
/// | unset (or unresolvable) | resolves | `{clientIdentifier}-DEFAULT-POOL` |
/// | unset | unresolvable | `DEFAULT-POOL` — **superseded (R7): `platform-DEFAULT-POOL`** |
///
/// R7's reasoning: the router merges this document with several Integral
/// tenant configs and pools merge by `code`, first-definition-wins, so an
/// unprefixed platform pool code could silently collide with (and lose to)
/// an Integral tenant's pool of the same name. Only the two platform-level
/// rows change; a client-owned pool's code is unaffected. [#composeCode] is
/// the one place this composition happens, reused by
/// [io.flowcatalyst.platform.dispatch.RouterConfigDocumentBuilder] so a
/// job's stamped code and the served router-config document can never
/// disagree on the shape.
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

    /// The router's global fallback pool — [RouterManager]'s own
    /// always-injected bare pool of this exact name. [#resolve] itself no
    /// longer returns this bare literal (R7: the fully-unresolvable case now
    /// composes `platform-DEFAULT-POOL` instead), but the constant is kept
    /// for [#DEFAULT_POOL_SUFFIX] and [#isDefaultPoolCode], and because it is
    /// still the name of a real pool the router always runs.
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
                return composeCode(ref.code(), ref.clientIdentifier());
            }
        }
        if (clientId != null) {
            String identifier = s.clientIdentifiers().get(clientId);
            if (identifier != null && !identifier.isEmpty()) {
                return identifier + DEFAULT_POOL_SUFFIX;
            }
        }
        // Unresolvable altogether: R7 makes this the platform tenant's
        // default-pool fallback rather than the bare global constant, so it
        // self-synthesises via RouterManager's "-DEFAULT-POOL" suffix rule
        // exactly like every other tenant's fallback, instead of relying on
        // RouterManager's separate always-injected bare DEFAULT_POOL.
        return ClientIdentifier.RESERVED_PLATFORM + DEFAULT_POOL_SUFFIX;
    }

    /// The client's identifier for `clientId` (`null` when `clientId` is
    /// `null` or unresolved), from the SAME cached `tnt_clients` snapshot
    /// [#resolve] reads — exposed so [SqsDispatchPublisher] can derive a
    /// claimed job's tenant without a second copy of this query (a claimed
    /// [io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow]
    /// carries only `clientId`, never the identifier itself). Never throws;
    /// an unresolved client falls back to
    /// [io.flowcatalyst.platform.client.ClientIdentifier#RESERVED_PLATFORM]
    /// at the call site, exactly as an unresolved client already does in
    /// [#resolve].
    public String clientIdentifier(String clientId) {
        if (clientId == null) {
            return null;
        }
        ensureFresh();
        String identifier = snapshot.clientIdentifiers().get(clientId);
        return (identifier == null || identifier.isEmpty()) ? null : identifier;
    }

    /// Composes the wire `poolCode` for a resolved pool row:
    /// `{clientIdentifier}-{poolCode}` when client-owned,
    /// `platform-{poolCode}` when platform-level (`clientIdentifier` `null`
    /// or empty — ruling R7). The one place this composition happens; see
    /// the class doc.
    public static String composeCode(String poolCode, String clientIdentifier) {
        String tenant = (clientIdentifier == null || clientIdentifier.isEmpty())
                ? ClientIdentifier.RESERVED_PLATFORM
                : clientIdentifier;
        return tenant + "-" + poolCode;
    }

    /// Whether `code` names a fallback pool — the global [#DEFAULT_POOL_CODE]
    /// or any per-client/per-tenant `{identifier}-DEFAULT-POOL` (R7 made
    /// `platform-DEFAULT-POOL` one more instance of this same suffix rule,
    /// so no change was needed here).
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

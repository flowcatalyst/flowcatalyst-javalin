package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/// Blocked / held message groups (spec §7.4, R-04): "which ordered groups are
/// stalled, in which pool, how many messages deep, under what pool settings"
/// — the view the specification calls out as existing specifically because
/// an ordered group blocked on a failed head is otherwise invisible from
/// outside the process, and doubly so while its pool is only draining after a
/// reconfigure removed it (`docs/spec/router-completion.md` §2 ruling 6).
///
/// A null [io.flowcatalyst.router.manager.RouterManager] is the same
/// "provider not configured" state the rest of this API uses: the list
/// answers `[]`.
final class GroupRoutes {

    /// Mounts this group. Called by [RouterApi#register].
    static void register(Routes routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/blocked-groups", ctx -> blockedGroups(ctx, s));
    }

    private static void blockedGroups(Exchange ctx, State s) {
        if (s.manager() == null) {
            ctx.json(List.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        // allPools(), deliberately not pools(): a pool still draining after a
        // reconfigure removed it must keep its groups visible here until it
        // finishes (`docs/spec/router-completion.md` §2 ruling 6).
        ctx.json(rows(s.manager().allPools()));
    }

    /// The join and the sort, separated from the HTTP so both can be tested
    /// with input deliberately in the wrong order — see
    /// [GroupFlushRoutes#groupFlushRows] and [PoolRoutes#mediatingRows] for
    /// the same trap this guards against: a `ConcurrentHashMap`'s iteration
    /// order, for a small fixed set of keys, can happen to land sorted by
    /// accident.
    static List<Wire.BlockedGroupView> rows(Map<String, Pool> pools) {
        return pools.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .flatMap(e -> e.getValue().groupSnapshot().stream()
                        .sorted(Comparator.comparing(Pool.GroupSnapshot::group))
                        .map(g -> toView(e.getKey(), e.getValue(), g)))
                .toList();
    }

    private static Wire.BlockedGroupView toView(String poolCode, Pool pool, Pool.GroupSnapshot group) {
        int rpm = pool.config().requestsPerMinute();
        Integer rateLimit = rpm == 0 ? null : rpm; // 0 -> unlimited -> omitted, matches WirePoolStats
        return new Wire.BlockedGroupView(poolCode, group.group(), group.depth(), group.draining(),
                group.suppressedUntil(), pool.config().concurrency(), rateLimit);
    }

    private GroupRoutes() {
    }
}

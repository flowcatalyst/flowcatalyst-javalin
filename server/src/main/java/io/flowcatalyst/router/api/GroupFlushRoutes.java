package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.policy.GroupFlushRegistry;
import io.flowcatalyst.router.pool.Pool;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/// Group-flush suppression visibility and the operator clear (spec §4.5,
/// §7.4; R-52, R-53).
///
/// A target's `flushGroup` response ACKs the rest of a message group without
/// ever calling out — from outside the process that reads as the group going
/// silent for no visible reason. This is the answer to "why is this group
/// quiet, and can I lift it early?": every pool's [GroupFlushRegistry]
/// enumerated in one list, plus a clear endpoint scoped to one pool/group.
///
/// A null [RouterManager] is the same "provider not configured" state the
/// rest of this API uses: the list answers `[]`.
final class GroupFlushRoutes {

    /// Mounts this group. Called by [RouterApi#register].
    static void register(JavalinDefaultRoutingApi routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/group-flushes", ctx -> groupFlushes(ctx, s));
        routes.post(p + "/monitoring/group-flushes/{pool}/{group}/clear", ctx -> clearGroupFlush(ctx, s));
    }

    private static void groupFlushes(Context ctx, State s) {
        if (s.manager() == null) {
            ctx.json(List.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        ctx.json(groupFlushRows(s.manager().pools()));
    }

    /// The sort, separated from the HTTP so the ordering can be tested with
    /// input that is deliberately in the wrong order.
    ///
    /// `pools` arrives from a `ConcurrentHashMap` (`RouterManager#pools`)
    /// whose iteration order, for a given fixed set of pool codes, happens
    /// to be stable run to run — so an implementation that forgot to sort
    /// by pool code can still pass a test that only exercises the endpoint
    /// with a couple of pool codes that hash into the "right" order by
    /// accident. [PoolRoutes#mediatingRows] documents the same trap.
    static List<Wire.GroupFlushView> groupFlushRows(Map<String, Pool> pools) {
        return pools.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .flatMap(e -> e.getValue().flushRegistry().active().stream()
                        .map(sup -> new Wire.GroupFlushView(e.getKey(), sup.group(), sup.until())))
                .toList();
    }

    private static void clearGroupFlush(Context ctx, State s) {
        if (s.manager() == null) {
            Http.serviceUnavailable(ctx, "pool updater not configured");
            return;
        }
        String poolCode = ctx.pathParam("pool");
        String group = ctx.pathParam("group");
        var pool = s.manager().pools().get(poolCode);
        if (pool == null) {
            Http.notFound(ctx, "pool not found: " + poolCode);
            return;
        }
        boolean cleared = pool.flushRegistry().clear(group);
        ctx.json(new Wire.GroupFlushClearResponse(cleared));
    }

    private GroupFlushRoutes() {
    }
}

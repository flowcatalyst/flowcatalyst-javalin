package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

/// Standby status, stream health and the config snapshot (§9.1).
///
/// The leftovers, grouped because each is a single read with no siblings.
/// Stream health is an honest stand-in — no provider is wired in this build,
/// and the handler says so.
final class AdminRoutes {

    /// Mounts this group. Called by [RouterApi#register].
    static void register(Routes routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/standby-status", ctx -> standbyStatus(ctx, s));
        routes.get(p + "/monitoring/stream-health", ctx -> streamHealth(ctx, s));
        routes.get(p + "/monitoring/stream-health/live", ctx -> streamProbe(ctx));
        routes.get(p + "/monitoring/stream-health/ready", ctx -> streamProbe(ctx));
        routes.get(p + "/api/config", ctx -> localConfig(ctx, s));
        routes.post(p + "/config/reload", ctx -> configReload(ctx, s));
    }

    private static void standbyStatus(Exchange ctx, State s) {
        if (s.election() == null) {
            ctx.json(new Wire.StandbyStatusResponse(false, true, "default"));
            return;
        }
        var cfg = s.electionConfig();
        boolean enabled = cfg != null && cfg.enabled();
        // R-56: the election's own per-process instance id, never the lock
        // key every instance in the group shares — a lock key answers
        // "which election", not "which process".
        ctx.json(new Wire.StandbyStatusResponse(enabled, s.election().isLeader(), s.election().instanceId()));
    }

    /// No stream processor provider is wired anywhere in this build, so this
    /// is always the documented no-provider fallback (spec §9.1 note; Go
    /// `handlers_misc.go: streamHealth`, nil-provider branch).
    private static void streamHealth(Exchange ctx, State s) {
        ctx.json(new Wire.StreamHealthResponse(false, "NOT_CONFIGURED", "no stream processor configured in this build"));
    }

    private static void streamProbe(Exchange ctx) {
        ctx.json(new Wire.StreamProbeResponse("NOT_CONFIGURED"));
    }

    private static void localConfig(Exchange ctx, State s) {
        ctx.json(new Wire.LocalConfigResponse(s.version(), s.warnings().count(), s.warnings().critical().size()));
    }

    /// Re-runs [RouterServer#applyConfiguration] against the live router
    /// (R-33). Leadership-gated: a follower answers 409 rather than
    /// silently starting consumers, which would break the per-group
    /// single-drainer invariant ordering depends on (spec §5.5). With no
    /// [RouterServer] wired at all (`s.server()` null — an instance built
    /// before this route existed, or one with no router), this degrades to
    /// the old no-op-200 shape rather than erroring: a dashboard reload
    /// button on such an instance still gets a 200, just `reloaded: false`.
    private static void configReload(Exchange ctx, State s) {
        var server = s.server();
        if (server == null) {
            ctx.json(Wire.ConfigReloadResponse.UNAVAILABLE);
            return;
        }
        if (!server.leader()) {
            Http.conflict(ctx, "not leader");
            return;
        }
        var result = server.applyConfiguration();
        if (result.isEmpty()) {
            ctx.json(Wire.ConfigReloadResponse.UNAVAILABLE);
            return;
        }
        var r = result.get();
        ctx.json(new Wire.ConfigReloadResponse(true, r.pools(), r.consumersStarted(), r.consumersStopped(),
                r.failedQueues()));
    }

    private AdminRoutes() {
    }
}

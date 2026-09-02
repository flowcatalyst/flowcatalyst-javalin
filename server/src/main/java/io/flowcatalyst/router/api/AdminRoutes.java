package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

/// Standby status, stream health and the config snapshot (§9.1).
///
/// The leftovers, grouped because each is a single read with no siblings. Two
/// are honest stand-ins rather than live data, and say so at the handler:
/// stream health has no provider wired in this build, and `/config/reload`
/// has no reloader.
final class AdminRoutes {

    /// Mounts this group. Called by [RouterApi#register].
    static void register(JavalinDefaultRoutingApi routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/standby-status", ctx -> standbyStatus(ctx, s));
        routes.get(p + "/monitoring/stream-health", ctx -> streamHealth(ctx, s));
        routes.get(p + "/monitoring/stream-health/live", ctx -> streamProbe(ctx));
        routes.get(p + "/monitoring/stream-health/ready", ctx -> streamProbe(ctx));
        routes.get(p + "/api/config", ctx -> localConfig(ctx, s));
        routes.post(p + "/config/reload", ctx -> configReload(ctx));
    }

    private static void standbyStatus(Context ctx, State s) {
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
    private static void streamHealth(Context ctx, State s) {
        ctx.json(new Wire.StreamHealthResponse(false, "NOT_CONFIGURED", "no stream processor configured in this build"));
    }

    private static void streamProbe(Context ctx) {
        ctx.json(new Wire.StreamProbeResponse("NOT_CONFIGURED"));
    }

    private static void localConfig(Context ctx, State s) {
        ctx.json(new Wire.LocalConfigResponse(s.version(), s.warnings().count(), s.warnings().critical().size()));
    }

    /// No config source / reload mechanism is ported yet, so this always
    /// answers the Go "no explicit Reloader wired" branch verbatim (200, not
    /// 503, so a dashboard reload button keeps working) rather than a 501 —
    /// `handlers_misc.go: configReload`, nil-`Reloader` case.
    private static void configReload(Context ctx) {
        ctx.json(new Wire.ConfigReloadResponse(true, "config watcher polls automatically"));
    }

    private AdminRoutes() {
    }
}

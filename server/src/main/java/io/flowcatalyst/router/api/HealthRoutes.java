package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/// Liveness, readiness and the aggregate health views (§9.1, §9.4).
///
/// `/health` and `/monitoring/health` answer the same snapshot in two shapes;
/// `/monitoring` is the composite the dashboard polls, and is here rather than
/// with the pools because what it is *for* is health — the pool rows are
/// context.
final class HealthRoutes {

    private static final Instant STARTED_AT = Instant.now();

    /// Mounts this group. Called by [RouterApi#register].
    static void register(JavalinDefaultRoutingApi routes, State s) {
        var p = s.prefix();
        routes.get(p + "/health", ctx -> health(ctx, s));
        routes.get(p + "/q/health", ctx -> health(ctx, s));
        routes.get(p + "/health/live", ctx -> ctx.json(new Wire.ProbeResponse("LIVE")));
        routes.get(p + "/health/ready", ctx -> readiness(ctx, s));
        routes.get(p + "/health/startup", ctx -> readiness(ctx, s));
        routes.get(p + "/monitoring", ctx -> monitoring(ctx, s));
        routes.get(p + "/monitoring/health", ctx -> monitoringHealth(ctx, s));
        routes.get(p + "/monitoring/consumer-health", ctx -> consumerHealth(ctx, s));
    }

    private static void health(Context ctx, State s) {
        var h = healthSnapshot(s);
        ctx.json(new Wire.SimpleHealthResponse(h.status(), s.version(), h.active(), h.critical()));
    }

    private static void readiness(Context ctx, State s) {
        var h = healthSnapshot(s);
        if (h.degraded()) {
            ctx.status(503).json(new Wire.ProbeResponse("NOT_READY"));
        } else {
            ctx.json(new Wire.ProbeResponse("READY"));
        }
    }

    private static void monitoringHealth(Context ctx, State s) {
        var h = healthSnapshot(s);
        int totalPools = s.manager() == null ? 0 : s.manager().pools().size();
        // Never-fed consumer model (spec §9.4: SetConsumerRunning/RecordConsumerPoll
        // are never called in production), so this always reports 0/0.
        int totalQueues = 0;
        int healthyQueues = 0;
        int breakersOpen = s.breakers() == null ? 0 : (int) s.breakers().snapshot().values().stream()
                .filter(st -> st.state() == CircuitBreaker.State.OPEN).count();
        // Go's HealthReport.Issues only ever gains a "N critical warnings"
        // entry (health.go:218-221) — the >20-active-warnings Degraded branch
        // adds nothing to Issues, so degradationReason can be null even when
        // status is DEGRADED. Reproduced verbatim, not "fixed".
        String degradationReason = h.critical() > 0 ? h.critical() + " critical warnings" : null;
        var details = new Wire.DashboardHealthDetails(totalQueues, healthyQueues, totalPools, totalPools,
                h.active(), h.critical(), breakersOpen, degradationReason);
        ctx.json(new Wire.DashboardHealthResponse(h.status(), Instant.now(),
                Duration.between(STARTED_AT, Instant.now()).toMillis(), details));
    }

    private static void consumerHealth(Context ctx, State s) {
        // Always {} — lists only STALLED consumers of the never-fed
        // HealthService (spec §9.1 row, §9.4). No consumer health tracker is
        // wired in Java at all, so this can never be non-empty.
        ctx.json(new Wire.ConsumerHealthResponse(Instant.now().toEpochMilli(), Instant.now(), Map.of()));
    }

    /// The composite view (spec §9.1): snake_case outer fields, a nested
    /// snake_case `health_report`, and a `pool_stats` array whose *own*
    /// fields are snake but whose `metrics` sub-object is camelCase — the
    /// mixed casing is the contract, not an inconsistency.
    ///
    /// `active_warnings` here is **all** unacknowledged warnings at any age
    /// (`s.warnings().unacknowledged()`), deliberately different from the
    /// ≤30-minute count inside `health_report` (§9.1 note; both are pinned
    /// by `RouterApiTest`).
    private static void monitoring(Context ctx, State s) {
        var h = healthSnapshot(s);
        int poolsHealthy = s.manager() == null ? 0 : s.manager().pools().size();
        // Same Issues rule as #monitoringHealth: only ever "N critical warnings".
        List<String> issues = h.critical() > 0 ? List.of(h.critical() + " critical warnings") : List.of();
        var healthReport = new Wire.WireHealthReport(h.status(), poolsHealthy, 0, 0, 0, h.active(), h.critical(), issues);
        List<Wire.WirePoolStats> poolStats = s.manager() == null
                ? List.of()
                : s.manager().pools().entrySet().stream().map(e -> PoolRoutes.wirePoolStats(e.getKey(), e.getValue(), s)).toList();
        ctx.json(new Wire.MonitoringResponse(h.status(), s.version(), healthReport, poolStats,
                s.warnings().unacknowledged().size(), h.critical()));
    }
    private record HealthSnapshot(String status, int active, int critical) {
        boolean degraded() {
            return status.equals("DEGRADED");
        }
    }

    /// The effective status rule (spec §9.4 table): Degraded if any unacked
    /// CRITICAL or active warnings > 20; Warning if active > 5; else Healthy.
    /// The pool/consumer clauses never fire (never-fed models) and are
    /// therefore not modelled at all.
    private static HealthSnapshot healthSnapshot(State s) {
        int active = s.warnings().active(Duration.ofMinutes(30)).size();
        int critical = s.warnings().critical().size();
        String status = critical > 0 || active > 20 ? "DEGRADED" : active > 5 ? "WARNING" : "HEALTHY";
        return new HealthSnapshot(status, active, critical);
    }

    private HealthRoutes() {
    }
}

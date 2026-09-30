package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.manager.RouterServer;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

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
    static void register(Routes routes, State s) {
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

    private static void health(Exchange ctx, State s) {
        var h = healthSnapshot(s);
        ctx.json(new Wire.SimpleHealthResponse(h.status(), s.version(), h.active(), h.critical()));
    }

    private static void readiness(Exchange ctx, State s) {
        var h = healthSnapshot(s);
        if (h.degraded()) {
            ctx.status(503).json(new Wire.ProbeResponse(
                    h.stalledReason() != null ? "NOT_READY: " + h.stalledReason() : "NOT_READY"));
        } else {
            ctx.json(new Wire.ProbeResponse("READY"));
        }
    }

    private static void monitoringHealth(Exchange ctx, State s) {
        var h = healthSnapshot(s);
        int totalPools = s.manager() == null ? 0 : s.manager().pools().size();
        int breakersOpen = s.breakers() == null ? 0 : (int) s.breakers().snapshot().values().stream()
                .filter(st -> st.state() == CircuitBreaker.State.OPEN).count();
        String degradationReason = h.issues().isEmpty() ? null : String.join("; ", h.issues());
        var details = new Wire.DashboardHealthDetails(h.consumersTotal(), h.consumersTotal() - h.stalled().size(),
                totalPools, totalPools, h.active(), h.critical(), breakersOpen, degradationReason);
        ctx.json(new Wire.DashboardHealthResponse(h.status(), Instant.now(),
                Duration.between(STARTED_AT, Instant.now()).toMillis(), details));
    }

    /// Lists only the STALLED consumers (Go `consumerHealth`).
    private static void consumerHealth(Exchange ctx, State s) {
        var now = Instant.now();
        var consumers = new java.util.LinkedHashMap<String, Wire.ConsumerHealthDetail>();
        for (var c : healthSnapshot(s).stalled()) {
            long lastMs = c.lastAlive().map(Instant::toEpochMilli).orElse(0L);
            long sinceMs = c.lastAlive().map(last -> Duration.between(last, now).toMillis()).orElse(-1L);
            consumers.put(c.queue(), new Wire.ConsumerHealthDetail(c.queue(), c.queue(), c.queue(), false, lastMs,
                    c.lastAlive().map(Instant::toString).orElse("never"), sinceMs,
                    sinceMs > 0 ? sinceMs / 1000 : -1, true));
        }
        ctx.json(new Wire.ConsumerHealthResponse(now.toEpochMilli(), now, consumers));
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
    private static void monitoring(Exchange ctx, State s) {
        var h = healthSnapshot(s);
        int poolsHealthy = s.manager() == null ? 0 : s.manager().pools().size();
        int consumersUnhealthy = h.stalled().size();
        var healthReport = new Wire.WireHealthReport(h.status(), poolsHealthy, 0,
                h.consumersTotal() - consumersUnhealthy, consumersUnhealthy, h.active(), h.critical(), h.issues());
        List<Wire.WirePoolStats> poolStats = s.manager() == null
                ? List.of()
                : s.manager().pools().entrySet().stream().map(e -> PoolRoutes.wirePoolStats(e.getKey(), e.getValue(), s)).toList();
        ctx.json(new Wire.MonitoringResponse(h.status(), s.version(), healthReport, poolStats,
                s.warnings().unacknowledged().size(), h.critical()));
    }

    /// @param stalled the queues whose poll loop has gone quiet (R-36); makes
    ///                readiness fail even though `status` may still read
    ///                HEALTHY when other consumers are polling
    /// @param issues  Go's `HealthReport.Issues`: one line per stalled
    ///                consumer, then the critical-warning count, then the
    ///                active-warning count once it degrades the status
    private record HealthSnapshot(String status, int active, int critical, int consumersTotal,
                                  List<RouterServer.ConsumerStat> stalled, List<String> issues) {
        boolean degraded() {
            return status.equals("DEGRADED") || !stalled.isEmpty();
        }

        String stalledReason() {
            return stalled.isEmpty() ? null : "consumer " + stalled.getFirst().queue() + " not polling";
        }
    }

    /// The status rule (Go `HealthReport`): Degraded if any unacked CRITICAL,
    /// every consumer stalled, or active warnings > 20; Warning if any
    /// consumer stalled or active > 5; else Healthy. The pool-success-rate
    /// clause is not modelled: nothing in Go feeds it either (no production
    /// caller of `RecordPoolResult`), so it never fires there.
    private static HealthSnapshot healthSnapshot(State s) {
        int active = s.warnings().active(Duration.ofMinutes(30)).size();
        int critical = s.warnings().critical().size();
        // A follower (`server` null or not running) has no consumers, so losing
        // leadership never itself fails readiness on this account.
        var consumers = s.server() == null || !s.server().running()
                ? List.<RouterServer.ConsumerStat>of()
                : s.server().consumerStats();
        var stalled = consumers.stream().filter(RouterServer.ConsumerStat::stalled).toList();
        int healthy = consumers.size() - stalled.size();

        var issues = new java.util.ArrayList<String>();
        stalled.forEach(c -> issues.add("Consumer " + c.queue() + " is stalled"));
        if (critical > 0) {
            issues.add(critical + " critical warnings");
        }
        // The count is a degradation cause in its own right; without an issue
        // a router degraded purely by warning volume reported no reason.
        if (active > 5) {
            issues.add(active + " active warnings (degrades above 20)");
        }
        String status = critical > 0 || (!stalled.isEmpty() && healthy == 0) || active > 20 ? "DEGRADED"
                : !stalled.isEmpty() || active > 5 ? "WARNING" : "HEALTHY";
        return new HealthSnapshot(status, active, critical, consumers.size(), stalled, List.copyOf(issues));
    }

    private HealthRoutes() {
    }
}

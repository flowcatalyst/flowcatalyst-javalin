package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.pool.Pool;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The pool views and the one pool mutation (§9.1).
///
/// `/monitoring/pools` and `/monitoring/pool-stats` are two shapes over the
/// same collectors; `/monitoring/mediating` is the live "what is inside a
/// worker right now" view, which a count alone cannot answer. The hot-update
/// route is here rather than with the other mutations because it is a pool
/// operation, and grouping by resource beats grouping by verb.
final class PoolRoutes {

    /// Default page size for `/monitoring/mediating`, matching Go.
    private static final int DEFAULT_MEDIATING_LIMIT = 200;

    /// What an untracked pool's metrics report (Go's `if s.Metrics != nil`
    /// guard, `poolStatsToDashboard`) — a fresh pool with no
    /// [RouterApi.State#poolMetrics] entry looks idle rather than absent.
    private static final PoolMetricsCollector.WindowedMetrics ZERO_WINDOW =
            new PoolMetricsCollector.WindowedMetrics(0, 0, 0, 0, 1.0, 0.0,
                    PoolMetricsCollector.ProcessingTimeMetrics.EMPTY, Instant.EPOCH, 0);
    private static final PoolMetricsCollector.Snapshot ZERO_METRICS =
            new PoolMetricsCollector.Snapshot(0, 0, 0, 0, 1.0,
                    PoolMetricsCollector.ProcessingTimeMetrics.EMPTY, ZERO_WINDOW, ZERO_WINDOW);

    /// Mounts this group. Called by [RouterApi#register].
    static void register(JavalinDefaultRoutingApi routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/pools", ctx -> monitoringPools(ctx, s));
        routes.get(p + "/monitoring/pool-stats", ctx -> poolStats(ctx, s));
        routes.get(p + "/monitoring/mediating", ctx -> monitoringMediating(ctx, s));
        routes.put(p + "/monitoring/pools/{poolCode}", ctx -> updatePool(ctx, s));
    }

    /// `GET /monitoring/mediating` — what is inside a pool worker right now.
    ///
    /// Sorted longest-first, because the question this answers is "what is
    /// stuck?" and the answer is always at the top. `limit` defaults to 200:
    /// a pool wedged against a dead target has every worker occupied, and an
    /// unbounded list of identical rows helps nobody.
    private static void monitoringMediating(Context ctx, State s) {
        if (s.manager() == null) {
            ctx.json(List.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        ctx.json(mediatingRows(
                s.manager().pools().values().stream().flatMap(pool -> pool.mediating().stream()).toList(),
                ctx.queryParam("poolCode"),
                Http.parsePositiveInt(ctx.queryParam("limit"), DEFAULT_MEDIATING_LIMIT),
                java.time.Instant.now()));
    }

    /// The filter/sort/limit, separated from the HTTP so the ordering can be
    /// tested with input that is deliberately in the wrong order.
    ///
    /// Through the endpoint it cannot be: the rows arrive from a
    /// `ConcurrentHashMap` whose iteration order happens to match the order
    /// they were added, so an unsorted implementation passes anyway. The test
    /// looked like it pinned the sort and did not.
    static List<Wire.WireMediating> mediatingRows(java.util.Collection<io.flowcatalyst.router.pool.Mediating> rows,
                                             String poolFilter, int limit, java.time.Instant now) {
        return rows.stream()
                .filter(row -> poolFilter == null || poolFilter.isBlank()
                        || row.poolCode().equalsIgnoreCase(poolFilter))
                .map(row -> new Wire.WireMediating(row.messageId(), row.poolCode(),
                        row.group() == null ? "" : row.group(), row.queue(), row.target(),
                        row.attempts(), Math.max(0, java.time.Duration.between(row.startedAt(), now).toMillis())))
                .sorted(java.util.Comparator.comparingLong(Wire.WireMediating::elapsedTimeMs).reversed())
                .limit(limit)
                .toList();
    }

    private static void monitoringPools(Context ctx, State s) {
        if (s.manager() == null) {
            ctx.json(List.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        ctx.json(s.manager().pools().entrySet().stream().map(e -> wirePoolStats(e.getKey(), e.getValue(), s)).toList());
    }

    /// `Wire.WirePoolStats` for one pool.
    static Wire.WirePoolStats wirePoolStats(String code, Pool pool, State s) {
        var collector = s.poolMetrics().get(code);
        var snapshot = collector == null ? ZERO_METRICS : collector.snapshot();
        int rpm = pool.config().requestsPerMinute();
        Integer rateLimit = rpm == 0 ? null : rpm; // 0 -> unlimited -> omitted (Go `RateLimitPerMinute()` returns nil)
        return new Wire.WirePoolStats(code, pool.config().concurrency(), pool.activeWorkers(), pool.queueSize(),
                pool.config().queueCapacity(), pool.messageGroupCount(), rateLimit, pool.rateLimited(), snapshot);
    }

    /// `time_window=5min|5m|30min|30m` select a window; anything else
    /// (absent, `all`, unknown) is all-time (Go `parseTimeWindow`).
    private static void poolStats(Context ctx, State s) {
        if (s.manager() == null) {
            ctx.json(Map.of()); // empty payload for lists
            return;
        }
        var window = Http.parseTimeWindow(ctx.queryParam("time_window"));
        Map<String, Wire.DashboardPoolStats> out = new LinkedHashMap<>();
        s.manager().pools().forEach((code, pool) -> out.put(code, dashboardPoolStats(pool, s.poolMetrics().get(code), window)));
        ctx.json(out);
    }

    private static Wire.DashboardPoolStats dashboardPoolStats(Pool pool, PoolMetricsCollector collector, Duration window) {
        long succeeded = 0;
        long failed = 0;
        long rateLimited = 0;
        double successRate = 1.0;
        double avgMs = 0;
        if (collector != null) {
            var snap = collector.snapshot();
            PoolMetricsCollector.WindowedMetrics w =
                    Duration.ofMinutes(5).equals(window) ? snap.last5Min()
                            : Duration.ofMinutes(30).equals(window) ? snap.last30Min() : null;
            if (w != null) {
                succeeded = w.successCount();
                failed = w.failureCount();
                rateLimited = w.rateLimitedCount();
                successRate = w.successRate();
                avgMs = w.processingTime().avgMs();
            } else {
                succeeded = snap.totalSuccess();
                failed = snap.totalFailure();
                rateLimited = snap.totalRateLimited();
                successRate = snap.successRate();
                avgMs = snap.processingTime().avgMs();
            }
        }
        int concurrency = pool.config().concurrency();
        int active = pool.activeWorkers();
        int available = Math.max(concurrency - active, 0);
        return new Wire.DashboardPoolStats(pool.config().code(), succeeded + failed, succeeded, failed, rateLimited,
                successRate, active, available, concurrency, pool.queueSize(), pool.config().queueCapacity(), avgMs);
    }

    private static void updatePool(Context ctx, State s) {
        if (s.manager() == null) {
            Http.serviceUnavailable(ctx, "pool updater not configured");
            return;
        }
        String poolCode = ctx.pathParam("poolCode");
        var req = ctx.bodyAsClass(Wire.PoolConfigUpdateRequest.class);
        Pool pool = s.manager().pools().get(poolCode);
        boolean ok = pool != null;
        // concurrency absent or <=0 -> unchanged; rate_limit_per_minute
        // present (incl. 0 = unlimited) -> always applied, absent -> unchanged.
        if (ok && req.concurrency() != null && req.concurrency() > 0) {
            ok = pool.updateConcurrency(req.concurrency());
        }
        if (ok && req.rateLimitPerMinute() != null) {
            pool.updateRateLimit(req.rateLimitPerMinute());
        }
        if (!ok) {
            Http.notFound(ctx, "pool not found or update rejected: " + poolCode);
            return;
        }
        ctx.json(new Wire.PoolConfigUpdateResponse(true, poolCode,
                new Wire.PoolConfigUpdateNewConfig(req.concurrency(), req.rateLimitPerMinute())));
    }

    private PoolRoutes() {
    }
}

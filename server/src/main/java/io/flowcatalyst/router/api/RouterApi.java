package io.flowcatalyst.router.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.lifecycle.BrokerStatsCache;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.observability.WarningStore;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.standby.LeaderElection;
import io.flowcatalyst.router.traffic.Traffic;
import io.flowcatalyst.router.wire.Message;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// The router's monitoring HTTP API (`docs/spec/router.md` §9.1). Mounted
/// under [State#prefix] (default `/router`). BasicAuth (§9.7) guards this
/// surface and is applied by the caller — see
/// `io.flowcatalyst.router.api.auth.BasicAuthFilter`, which strips the mount
/// prefix before deciding whether a path is public.
///
/// ### Routes NOT registered here, and why
///
/// Every route below needs a live data source this module is allowed to read
/// (`Claude.md` scope: `RouterManager`, `Pool`, `BreakerRegistry`,
/// `CircuitBreaker`, `InFlightTracker`, `PoolMetricsCollector`,
/// `WarningStore`, `Consumer`, plus — found while reading — `LeaderElection`).
/// Several §9.1 rows have **no such source yet** and are deliberately absent
/// rather than faked:
///
///   - `GET/POST /messages`, `POST /api/seed/messages` — need a publisher
///     abstraction that does not exist yet.
///   - `POST /config/reload` beyond the "no reloader wired" branch — no
///     config-source/reload mechanism ported; see [#configReload].
///   - `GET /monitoring/dashboard`, `/dashboard.html` — served by
///     `io.flowcatalyst.router.api.dashboard.DashboardHandler`, which is a
///     consumer of this API rather than part of it.
///   - `GET /metrics` (Prometheus) — rendered by
///     `io.flowcatalyst.router.prometheus.RouterPrometheusCollector`; only
///     the alias under this prefix is still to be mounted.
///   - `GET /openapi.json`, `/docs` — huma-generated docs; no equivalent
///     generator wired for the router surface.
///
/// `GET /monitoring/standby-status` — buildable: [LeaderElection] was found
/// while reading the router package even though the task brief didn't list
/// it. `DELETE /warnings` and `DELETE /warnings/old` are the two exceptions
/// that dropped for a different reason: [WarningStore] has no bulk-remove
/// method, only `raise`/`acknowledge`/`cleanup`/the read accessors.
///
/// ### Queue depth and traffic (2026-08-26)
///
/// `GET /monitoring/queues`, `GET /monitoring/queue-stats`,
/// `POST /monitoring/broker-stats/refresh` and `GET /monitoring/traffic-status`
/// are now wired, over [BrokerStatsCache] and [Traffic] on [State]. Both are
/// nullable and degrade the §9.1 way: an empty payload for the two lists, 503
/// for the refresh mutation, and the `disabled` status for traffic.
///
/// Two things worth knowing before trusting the numbers:
///
///   - **`totalDeferred` is always 0**, and that is exact parity rather than a
///     stand-in. Go carries a `Defer` verb on every queue backend with the
///     counter behind this field — and **no production caller**, so Go reports
///     0 too. Java never grew the verb: a deferral is a `nack` with a delay,
///     counted as a nack on both sides, so nothing is being lost from the
///     column. It is kept on the wire because the dashboard reads it.
///   - **A queue the cache could not sample keeps its previous reading**
///     rather than reporting zero depth, so a row here can be stale during an
///     outage — which is the honest failure. `ageSeconds` on the refresh
///     response is how stale.
///
/// ### `GET /monitoring/in-flight-messages/detail`
///
/// The three statuses are the value of the endpoint: `MEDIATING` (inside a
/// pool worker right now — the join between [Pool#mediating()] and the tracker
/// entry), `RETRY_BACKOFF` (attempts > 0 and not in a worker), and
/// `TRACKED_IDLE` (neither: buffered behind its ordered group, waiting for a
/// slot, **or** a phantom whose broker has stopped redelivering). The last is
/// the one an operator is hunting, and its signature is `lastSeenElapsedMs`
/// growing without bound while `elapsedTimeMs` grows with it.
///
/// ### `GET /monitoring`, `/monitoring/pools`, `/monitoring/pool-stats`
///
/// [Pool] exposes `activeWorkers()`, `messageGroupCount()` and `rateLimited()`
/// (the pool's *own* limiter holding messages back right now — distinct from
/// a target answering 429, which is counted separately so conflating the two
/// does not hide which side is the bottleneck), and [State#poolMetrics]
/// supplies the per-pool [PoolMetricsCollector]. All three routes are fully
/// wired to live data; no stand-ins remain here.
public final class RouterApi {

    /// Default page size for `/monitoring/mediating`, matching Go.
    private static final int DEFAULT_MEDIATING_LIMIT = 200;

    private static final Instant STARTED_AT = Instant.now();

    /// What an untracked pool's metrics report (Go's `if s.Metrics != nil`
    /// guard, `poolStatsToDashboard`) — a fresh pool with no [State#poolMetrics]
    /// entry looks idle rather than absent.
    private static final PoolMetricsCollector.WindowedMetrics ZERO_WINDOW =
            new PoolMetricsCollector.WindowedMetrics(0, 0, 0, 0, 1.0, 0.0,
                    PoolMetricsCollector.ProcessingTimeMetrics.EMPTY, Instant.EPOCH, 0);
    private static final PoolMetricsCollector.Snapshot ZERO_METRICS =
            new PoolMetricsCollector.Snapshot(0, 0, 0, 0, 1.0,
                    PoolMetricsCollector.ProcessingTimeMetrics.EMPTY, ZERO_WINDOW, ZERO_WINDOW);

    /// `queue-stats`' `totalDeferred`, which is structurally zero on both
    /// sides. Go carries a `Defer` verb on every backend and the counter this
    /// field reports — and no production caller, so Go answers 0 as well.
    /// Java never grew the verb: a deferral is a `nack` with a delay and is
    /// counted as a nack, so the column loses nothing that the nack count does
    /// not already hold. Named rather than inlined so the next reader finds
    /// the reason instead of a bare literal.
    private static final long DEFERRALS_ARE_NACKS = 0;

    /// `queue-stats`' `throughput`. Go hard-codes 0.0 — it never computed a
    /// rate — and a plausible-looking number invented here would be worse than
    /// an obviously absent one, because a dashboard would plot it.
    private static final double THROUGHPUT_NOT_COMPUTED = 0.0;

    /// See `#forceAck`: paired with `brokerAcked:false` when
    /// [io.flowcatalyst.router.queue.Consumer#ack] reports the broker did
    /// **not** confirm the removal (still best-effort/never-throws, but now
    /// honestly observable — see the interface's own javadoc).
    private static final String BROKER_ACK_NOT_CONFIRMED =
            "broker did not confirm the removal; the message may still redeliver";

    private RouterApi() {
    }

    /// The handlers' dependencies. `manager`, `breakers` and `election` are
    /// nullable — a null models the same "provider not configured" state Go
    /// represents with a nil pointer (§9.1: "Provider-absent degradation is
    /// uniform: 503 for mutations/lookups, empty payload for lists").
    ///
    /// @param manager        pools + per-queue consumer lookup; also doubles
    ///                       as the "pool updater" and the "in-flight acker"
    ///                       — Go models those as separate nilable interfaces,
    ///                       Java has one object that can do both
    /// @param tracker        always present — core to the router, never gated
    /// @param warnings       always present — core to the router, never gated
    /// @param breakers       null → circuit-breaker lookups/mutations 503,
    ///                       the list route answers `{}`
    /// @param election       null → `/monitoring/standby-status` answers the
    ///                       Go "no leader adapter" default (`enabled:false,
    ///                       is_leader:true, instance_id:"default"`)
    /// @param electionConfig the election's static config, needed because
    ///                       [LeaderElection#instanceId] returns the
    ///                       process UUID, not the lock key the wire shape
    ///                       wants (Go `leaderAdapter.InstanceID`, `api.go:374`)
    /// @param version        reported on `/health`, `/monitoring/health` is
    ///                       exempt, `/api/config`; `null`/blank defaults to `"dev"`
    ///                       (Go `api.Version = "dev"`)
    /// @param prefix         mount prefix; `null`/blank defaults to `/router`
    /// @param mocks          counters for `/api/test/*`; owned entirely by
    ///                       this API, not read from elsewhere
    /// @param poolMetrics    per-pool [PoolMetricsCollector], keyed the same
    ///                       as `manager.pools()`. [Pool] does not expose its
    ///                       own metrics collector (it is typed as the
    ///                       [io.flowcatalyst.router.pool.PoolMetrics]
    ///                       interface internally with no getter), so the
    ///                       caller that built each pool passes its collector
    ///                       here separately. A pool with no entry reports
    ///                       zeroed metrics (matches Go's `if s.Metrics !=
    ///                       nil` guard); `null`/omitted defaults to `Map.of()`
    /// @param traffic       load-balancer registration state; `null` →
    ///                       `/monitoring/traffic-status` answers the disabled
    ///                       shape, which is also what [Traffic#DISABLED]
    ///                       reports, so an unconfigured deployment and an
    ///                       unwired one read the same
    /// @param brokerStats   the **same** cache the housekeeping loop samples
    ///                       into, not a second one — two caches would answer
    ///                       from two schedules and disagree on one dashboard.
    ///                       `null` → the two queue lists answer empty and the
    ///                       refresh mutation 503s
    public record State(RouterManager manager, InFlightTracker tracker, WarningStore warnings,
                        BreakerRegistry breakers, LeaderElection election, LeaderElection.Config electionConfig,
                        String version, String prefix, MockCounters mocks,
                        Map<String, PoolMetricsCollector> poolMetrics,
                        Traffic traffic, BrokerStatsCache brokerStats) {

        public State {
            Objects.requireNonNull(tracker, "tracker");
            Objects.requireNonNull(warnings, "warnings");
            version = version == null || version.isBlank() ? "dev" : version;
            prefix = prefix == null || prefix.isBlank() ? "/router" : prefix;
            mocks = mocks == null ? new MockCounters() : mocks;
            poolMetrics = poolMetrics == null ? Map.of() : Map.copyOf(poolMetrics);
        }
    }

    /// Hit counters for `/api/test/*` (Go `handlers_mocks.go`'s `s.Mocks`).
    /// Plain mutable counters, not a record: this is the API's own state,
    /// not something read from another component.
    public static final class MockCounters {
        final AtomicLong fast = new AtomicLong();
        final AtomicLong slow = new AtomicLong();
        final AtomicLong faulty = new AtomicLong();
        final AtomicLong faultySuccess = new AtomicLong();
        final AtomicLong faultyFail = new AtomicLong();
        final AtomicLong fail = new AtomicLong();
        final AtomicLong success = new AtomicLong();
        final AtomicLong pending = new AtomicLong();
        final AtomicLong clientError = new AtomicLong();
        final AtomicLong serverError = new AtomicLong();

        void reset() {
            fast.set(0);
            slow.set(0);
            faulty.set(0);
            faultySuccess.set(0);
            faultyFail.set(0);
            fail.set(0);
            success.set(0);
            pending.set(0);
            clientError.set(0);
            serverError.set(0);
        }
    }

    /// Mounts every route this module can honestly serve. See the class doc
    /// for what is deliberately absent.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        var p = s.prefix();

        // ── Health (§9.1, §9.4) ──────────────────────────────────────────
        routes.get(p + "/health", ctx -> health(ctx, s));
        routes.get(p + "/q/health", ctx -> health(ctx, s));
        routes.get(p + "/health/live", ctx -> ctx.json(new ProbeResponse("LIVE")));
        routes.get(p + "/health/ready", ctx -> readiness(ctx, s));
        routes.get(p + "/health/startup", ctx -> readiness(ctx, s));

        // ── Monitoring: health / consumer-health ────────────────────────
        routes.get(p + "/monitoring", ctx -> monitoring(ctx, s));
        routes.get(p + "/monitoring/health", ctx -> monitoringHealth(ctx, s));
        routes.get(p + "/monitoring/consumer-health", ctx -> consumerHealth(ctx, s));
        routes.get(p + "/monitoring/pools", ctx -> monitoringPools(ctx, s));
        routes.get(p + "/monitoring/pool-stats", ctx -> poolStats(ctx, s));
        routes.get(p + "/monitoring/mediating", ctx -> monitoringMediating(ctx, s));

        // ── Monitoring: warnings ─────────────────────────────────────────
        routes.get(p + "/monitoring/warnings", ctx -> monitoringWarnings(ctx, s));
        routes.get(p + "/monitoring/warnings/unacknowledged", ctx -> unacknowledgedWarnings(ctx, s));
        routes.get(p + "/monitoring/warnings/severity/{severity}", ctx -> warningsBySeverity(ctx, s));
        routes.post(p + "/monitoring/warnings/{id}/acknowledge", ctx -> acknowledgeWarning(ctx, s));

        // ── Monitoring: circuit breakers ─────────────────────────────────
        routes.get(p + "/monitoring/circuit-breakers", ctx -> circuitBreakers(ctx, s));
        routes.get(p + "/monitoring/circuit-breakers/{name}/state", ctx -> circuitBreakerState(ctx, s));
        routes.post(p + "/monitoring/circuit-breakers/{name}/reset", ctx -> resetBreaker(ctx, s));
        routes.post(p + "/monitoring/circuit-breakers/reset-all", ctx -> resetAllBreakers(ctx, s));

        // ── Monitoring: in-flight messages ───────────────────────────────
        routes.get(p + "/monitoring/in-flight-messages", ctx -> inFlightList(ctx, s));
        routes.get(p + "/monitoring/in-flight-messages/check", ctx -> inFlightCheck(ctx, s));
        routes.get(p + "/monitoring/in-flight-messages/detail", ctx -> inFlightDetail(ctx, s));
        routes.post(p + "/monitoring/in-flight-messages/check-batch", ctx -> inFlightCheckBatch(ctx, s));
        routes.post(p + "/monitoring/in-flight-messages/{messageId}/ack", ctx -> forceAck(ctx, s));

        // ── Monitoring: queue depth, broker stats, traffic ───────────────
        routes.get(p + "/monitoring/queues", ctx -> queues(ctx, s));
        routes.get(p + "/monitoring/queue-stats", ctx -> queueStats(ctx, s));
        routes.post(p + "/monitoring/broker-stats/refresh", ctx -> brokerStatsRefresh(ctx, s));
        routes.get(p + "/monitoring/traffic-status", ctx -> trafficStatus(ctx, s));

        // ── Monitoring: pool update, standby, stream health ──────────────
        routes.put(p + "/monitoring/pools/{poolCode}", ctx -> updatePool(ctx, s));
        routes.get(p + "/monitoring/standby-status", ctx -> standbyStatus(ctx, s));
        routes.get(p + "/monitoring/stream-health", ctx -> streamHealth(ctx, s));
        routes.get(p + "/monitoring/stream-health/live", ctx -> streamProbe(ctx));
        routes.get(p + "/monitoring/stream-health/ready", ctx -> streamProbe(ctx));

        // ── Plain /warnings surface ───────────────────────────────────────
        routes.get(p + "/warnings", ctx -> listWarnings(ctx, s));
        routes.post(p + "/warnings/{id}/acknowledge", ctx -> acknowledgeWarning(ctx, s));
        routes.post(p + "/warnings/acknowledge-all", ctx -> acknowledgeAllWarnings(ctx, s));
        routes.get(p + "/warnings/critical", ctx -> criticalWarnings(ctx, s));
        routes.get(p + "/warnings/unacknowledged", ctx -> unacknowledgedWarnings(ctx, s));
        routes.get(p + "/warnings/severity/{severity}", ctx -> warningsBySeverity(ctx, s));

        // ── Config snapshot / reload ──────────────────────────────────────
        routes.get(p + "/api/config", ctx -> localConfig(ctx, s));
        routes.post(p + "/config/reload", ctx -> configReload(ctx));

        // ── Dev mock targets (§9.1, always on) ────────────────────────────
        routes.post(p + "/api/test/fast", ctx -> testFast(ctx, s));
        routes.post(p + "/api/test/success", ctx -> testSuccess(ctx, s));
        routes.post(p + "/api/test/slow", ctx -> testSlow(ctx, s));
        routes.post(p + "/api/test/faulty", ctx -> testFaulty(ctx, s));
        routes.post(p + "/api/test/fail", ctx -> testFail(ctx, s));
        routes.post(p + "/api/test/server-error", ctx -> testServerError(ctx, s));
        routes.post(p + "/api/test/client-error", ctx -> testClientError(ctx, s));
        routes.post(p + "/api/test/pending", ctx -> testPending(ctx, s));
        routes.get(p + "/api/test/stats", ctx -> testStats(ctx, s));
        routes.post(p + "/api/test/stats/reset", ctx -> testStatsReset(ctx, s));
        routes.post(p + "/api/benchmark/process", ctx -> testFast(ctx, s));
        routes.post(p + "/api/benchmark/process-slow", ctx -> testSlow(ctx, s));
        routes.get(p + "/api/benchmark/stats", ctx -> testStats(ctx, s));
        routes.post(p + "/api/benchmark/reset", ctx -> testStatsReset(ctx, s));
    }

    // ── Health ────────────────────────────────────────────────────────────

    private static void health(Context ctx, State s) {
        var h = healthSnapshot(s);
        ctx.json(new SimpleHealthResponse(h.status(), s.version(), h.active(), h.critical()));
    }

    private static void readiness(Context ctx, State s) {
        var h = healthSnapshot(s);
        if (h.degraded()) {
            ctx.status(503).json(new ProbeResponse("NOT_READY"));
        } else {
            ctx.json(new ProbeResponse("READY"));
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
        var details = new DashboardHealthDetails(totalQueues, healthyQueues, totalPools, totalPools,
                h.active(), h.critical(), breakersOpen, degradationReason);
        ctx.json(new DashboardHealthResponse(h.status(), Instant.now(),
                Duration.between(STARTED_AT, Instant.now()).toMillis(), details));
    }

    private static void consumerHealth(Context ctx, State s) {
        // Always {} — lists only STALLED consumers of the never-fed
        // HealthService (spec §9.1 row, §9.4). No consumer health tracker is
        // wired in Java at all, so this can never be non-empty.
        ctx.json(new ConsumerHealthResponse(Instant.now().toEpochMilli(), Instant.now(), Map.of()));
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
        var healthReport = new WireHealthReport(h.status(), poolsHealthy, 0, 0, 0, h.active(), h.critical(), issues);
        List<WirePoolStats> poolStats = s.manager() == null
                ? List.of()
                : s.manager().pools().entrySet().stream().map(e -> wirePoolStats(e.getKey(), e.getValue(), s)).toList();
        ctx.json(new MonitoringResponse(h.status(), s.version(), healthReport, poolStats,
                s.warnings().unacknowledged().size(), h.critical()));
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
                parsePositiveInt(ctx.queryParam("limit"), DEFAULT_MEDIATING_LIMIT),
                java.time.Instant.now()));
    }

    /// The filter/sort/limit, separated from the HTTP so the ordering can be
    /// tested with input that is deliberately in the wrong order.
    ///
    /// Through the endpoint it cannot be: the rows arrive from a
    /// `ConcurrentHashMap` whose iteration order happens to match the order
    /// they were added, so an unsorted implementation passes anyway. The test
    /// looked like it pinned the sort and did not.
    static List<WireMediating> mediatingRows(java.util.Collection<io.flowcatalyst.router.pool.Mediating> rows,
                                             String poolFilter, int limit, java.time.Instant now) {
        return rows.stream()
                .filter(row -> poolFilter == null || poolFilter.isBlank()
                        || row.poolCode().equalsIgnoreCase(poolFilter))
                .map(row -> new WireMediating(row.messageId(), row.poolCode(),
                        row.group() == null ? "" : row.group(), row.queue(), row.target(),
                        row.attempts(), Math.max(0, java.time.Duration.between(row.startedAt(), now).toMillis())))
                .sorted(java.util.Comparator.comparingLong(WireMediating::elapsedTimeMs).reversed())
                .limit(limit)
                .toList();
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            // A junk limit is an operator typo, not a reason to 500 on a
            // read-only dashboard call.
            return fallback;
        }
    }

    /// One row of `GET /monitoring/mediating`. Field names and order match Go.
    public record WireMediating(String messageId, String poolCode, String group, String queue,
                                String target, int attempts, long elapsedTimeMs) {
    }

    private static void monitoringPools(Context ctx, State s) {
        if (s.manager() == null) {
            ctx.json(List.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        ctx.json(s.manager().pools().entrySet().stream().map(e -> wirePoolStats(e.getKey(), e.getValue(), s)).toList());
    }

    /// `WirePoolStats` for one pool.
    private static WirePoolStats wirePoolStats(String code, Pool pool, State s) {
        var collector = s.poolMetrics().get(code);
        var snapshot = collector == null ? ZERO_METRICS : collector.snapshot();
        int rpm = pool.config().requestsPerMinute();
        Integer rateLimit = rpm == 0 ? null : rpm; // 0 -> unlimited -> omitted (Go `RateLimitPerMinute()` returns nil)
        return new WirePoolStats(code, pool.config().concurrency(), pool.activeWorkers(), pool.queueSize(),
                pool.config().queueCapacity(), pool.messageGroupCount(), rateLimit, pool.rateLimited(), snapshot);
    }

    /// `time_window=5min|5m|30min|30m` select a window; anything else
    /// (absent, `all`, unknown) is all-time (Go `parseTimeWindow`).
    private static void poolStats(Context ctx, State s) {
        if (s.manager() == null) {
            ctx.json(Map.of()); // empty payload for lists
            return;
        }
        var window = parseTimeWindow(ctx.queryParam("time_window"));
        Map<String, DashboardPoolStats> out = new LinkedHashMap<>();
        s.manager().pools().forEach((code, pool) -> out.put(code, dashboardPoolStats(pool, s.poolMetrics().get(code), window)));
        ctx.json(out);
    }

    private static Duration parseTimeWindow(String raw) {
        if (raw == null) {
            return Duration.ZERO;
        }
        return switch (raw.trim()) {
            case "5min", "5m" -> Duration.ofMinutes(5);
            case "30min", "30m" -> Duration.ofMinutes(30);
            default -> Duration.ZERO;
        };
    }

    private static DashboardPoolStats dashboardPoolStats(Pool pool, PoolMetricsCollector collector, Duration window) {
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
        return new DashboardPoolStats(pool.config().code(), succeeded + failed, succeeded, failed, rateLimited,
                successRate, active, available, concurrency, pool.queueSize(), pool.config().queueCapacity(), avgMs);
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

    // ── Warnings ──────────────────────────────────────────────────────────

    private static void monitoringWarnings(Context ctx, State s) {
        var list = new ArrayList<>(s.warnings().active(Duration.ofMinutes(30)));
        list.sort(Comparator.comparing(WarningStore.Notice::createdAt).reversed());
        ctx.json(list.stream().map(RouterApi::wire).toList());
    }

    private static void unacknowledgedWarnings(Context ctx, State s) {
        ctx.json(s.warnings().unacknowledged().stream().map(RouterApi::wire).toList());
    }

    private static void warningsBySeverity(Context ctx, State s) {
        String want = ctx.pathParam("severity");
        ctx.json(s.warnings().snapshot().warnings().stream()
                .filter(w -> matchesSeverity(w.severity(), want))
                .map(RouterApi::wire)
                .toList());
    }

    private static void criticalWarnings(Context ctx, State s) {
        // Every CRITICAL warning, acknowledged or not (spec: "acked or not") —
        // deliberately NOT WarningStore#critical(), which is unacked-only.
        ctx.json(s.warnings().snapshot().warnings().stream()
                .filter(w -> w.severity() == Warnings.Severity.CRITICAL)
                .map(RouterApi::wire)
                .toList());
    }

    private static void listWarnings(Context ctx, State s) {
        List<WarningStore.Notice> base = "false".equals(ctx.queryParam("acknowledged"))
                ? s.warnings().unacknowledged()
                : s.warnings().snapshot().warnings();
        var filtered = new ArrayList<>(base);
        String severity = queryParam(ctx, "severity");
        if (!severity.isEmpty()) {
            filtered.removeIf(w -> !matchesSeverity(w.severity(), severity));
        }
        String category = queryParam(ctx, "category");
        if (!category.isEmpty()) {
            filtered.removeIf(w -> !w.category().equalsIgnoreCase(category));
        }
        filtered.sort(Comparator.comparing(WarningStore.Notice::createdAt).reversed());
        ctx.json(filtered.stream().map(RouterApi::wire).toList());
    }

    private static void acknowledgeWarning(Context ctx, State s) {
        String idText = ctx.pathParam("id");
        UUID id;
        try {
            id = UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            notFound(ctx, "Warning not found: " + idText);
            return;
        }
        if (s.warnings().acknowledge(id)) {
            ctx.json(new AcknowledgedResponse(true));
        } else {
            notFound(ctx, "Warning not found: " + idText);
        }
    }

    private static void acknowledgeAllWarnings(Context ctx, State s) {
        long n = 0;
        for (var w : s.warnings().unacknowledged()) {
            if (s.warnings().acknowledge(w.id())) {
                n++;
            }
        }
        ctx.json(new AcknowledgedCountResponse(n));
    }

    /// `WARN` is an alias for `WARNING`; otherwise case-insensitive equality
    /// (Go `matchesSeverity`, `handlers_warnings.go:92-97`).
    private static boolean matchesSeverity(Warnings.Severity have, String want) {
        String w = want.toUpperCase(Locale.ROOT);
        if (w.equals("WARN")) {
            return have == Warnings.Severity.WARNING;
        }
        return have.name().equalsIgnoreCase(w);
    }

    private static WireWarning wire(WarningStore.Notice n) {
        return new WireWarning(n.id().toString(), n.category(), n.severity().name(), n.message(), n.source(),
                n.createdAt(), n.acknowledged(), n.acknowledgedAt());
    }

    // ── Circuit breakers ──────────────────────────────────────────────────

    private static void circuitBreakers(Context ctx, State s) {
        if (s.breakers() == null) {
            ctx.json(Map.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        Map<String, DashboardCircuitBreaker> out = new LinkedHashMap<>();
        s.breakers().snapshot().forEach((name, stats) -> out.put(name, toDashboard(name, stats)));
        ctx.json(out);
    }

    private static DashboardCircuitBreaker toDashboard(String name, CircuitBreaker.Stats st) {
        long total = st.successes() + st.failures();
        double rate = total > 0 ? (double) st.failures() / total : 0.0;
        // rejectedCalls and bufferSize are always 0 — spec §9.1: the Java
        // CircuitBreaker never rejects a buffered-call count separately, and
        // has no notion of "buffer size" distinct from its fixed window.
        return new DashboardCircuitBreaker(name, st.state().wireValue(), st.successes(), st.failures(), 0, rate,
                st.recentFailures(), 0);
    }

    private static void circuitBreakerState(Context ctx, State s) {
        if (s.breakers() == null) {
            serviceUnavailable(ctx, "breakers not configured");
            return;
        }
        String name = ctx.pathParam("name"); // Javalin already URL-decodes the segment
        var stats = s.breakers().snapshot().get(name);
        if (stats == null) {
            notFound(ctx, "breaker not found: " + name);
            return;
        }
        ctx.json(new CircuitBreakerStateResponse(name, stats.state().wireValue(), stats.successes(), stats.failures(),
                stats.recentFailures()));
    }

    private static void resetBreaker(Context ctx, State s) {
        if (s.breakers() == null) {
            serviceUnavailable(ctx, "breakers not configured");
            return;
        }
        String name = ctx.pathParam("name");
        if (!s.breakers().reset(name)) {
            notFound(ctx, "breaker not found: " + name);
            return;
        }
        ctx.json(new BreakerResetResponse(true, name));
    }

    private static void resetAllBreakers(Context ctx, State s) {
        if (s.breakers() == null) {
            serviceUnavailable(ctx, "breakers not configured");
            return;
        }
        ctx.json(new BreakerResetAllResponse(s.breakers().resetAll()));
    }

    // ── In-flight messages ───────────────────────────────────────────────

    private static void inFlightList(Context ctx, State s) {
        int limit = queryInt(ctx, "limit", 100);
        if (limit <= 0) {
            limit = 100;
        }
        String idFilter = queryParam(ctx, "messageId").toLowerCase(Locale.ROOT);
        String poolFilter = queryParam(ctx, "poolCode");
        var now = Instant.now();
        var all = new ArrayList<InFlightMessageInfo>();
        for (var im : s.tracker().snapshot()) {
            if (!idFilter.isEmpty() && !im.messageId().toLowerCase(Locale.ROOT).contains(idFilter)) {
                continue;
            }
            if (!poolFilter.isEmpty() && !im.poolCode().equalsIgnoreCase(poolFilter)) {
                continue;
            }
            all.add(new InFlightMessageInfo(im.messageId(),
                    im.brokerMessageId().isEmpty() ? null : im.brokerMessageId(),
                    im.queueIdentifier(), im.poolCode(), Duration.between(im.startedAt(), now).toMillis(),
                    im.startedAt(), im.messageGroupId(), im.attempts()));
        }
        // Full filtered set sorted elapsed DESC, THEN truncated (spec note;
        // pins TestInFlightMessages_OrderedByElapsedDesc).
        all.sort(Comparator.comparingLong(InFlightMessageInfo::elapsedTimeMs).reversed());
        if (all.size() > limit) {
            all = new ArrayList<>(all.subList(0, limit));
        }
        ctx.json(all);
    }

    private static void inFlightCheck(Context ctx, State s) {
        String messageId = queryParam(ctx, "messageId");
        for (var im : s.tracker().snapshot()) {
            if (im.messageId().equals(messageId)) {
                ctx.json(new InFlightCheckResponse(messageId, true, im.poolCode(), im.queueIdentifier()));
                return;
            }
        }
        ctx.json(new InFlightCheckResponse(messageId, false, null, null));
    }

    private static void inFlightCheckBatch(Context ctx, State s) {
        var body = ctx.bodyAsClass(InFlightCheckBatchRequest.class);
        Set<String> live = new HashSet<>();
        for (var im : s.tracker().snapshot()) {
            live.add(im.messageId());
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (var id : body.messageIds()) {
            out.put(id, live.contains(id));
        }
        ctx.json(out);
    }

    /// Force-ACK (spec: "operator override"). Order matches Go
    /// (`handlers_mutations.go: inFlightForceAck`): subsystem-absence (503)
    /// before not-tracked (404).
    ///
    /// `wasMediating` warns that a delivery attempt was **still running inside
    /// a worker** when the entry was cleared; that attempt finishes on its own
    /// and may still reach the target after this responds. It is read from
    /// [Pool#mediating()] — it was hard-coded `false` until 2026-08-26, which
    /// told every operator force-acking a genuinely wedged message that
    /// nothing was in flight for it.
    ///
    /// `brokerAcked`/`brokerAckError`: [io.flowcatalyst.router.queue.Consumer#ack]
    /// now returns whether the broker confirmed the removal (still
    /// best-effort/never-throws — see the interface's own javadoc, which
    /// names exactly this force-ack case as the reason). `brokerAcked` is
    /// the real outcome; `brokerAckError` is set only when it is `false`, so
    /// an operator is never told a delete is confirmed when it is not.
    private static void forceAck(Context ctx, State s) {
        if (s.manager() == null) {
            serviceUnavailable(ctx, "in-flight ack not configured");
            return;
        }
        String messageId = ctx.pathParam("messageId");
        InFlightMessage entry = null;
        for (var im : s.tracker().snapshot()) {
            if (im.messageId().equals(messageId)) {
                entry = im;
                break;
            }
        }
        if (entry == null) {
            notFound(ctx, "message not in pipeline: " + messageId);
            return;
        }
        var consumer = s.manager().consumer(entry.queueIdentifier());
        if (consumer.isEmpty()) {
            serviceUnavailable(ctx, "no acker registered for queue " + entry.queueIdentifier());
            return;
        }
        var ackTarget = new QueuedMessage(
                new Message(entry.messageId(), entry.poolCode(), null, null, null, "", entry.messageGroupId(),
                        false, null),
                entry.brokerMessageId(), entry.receiptHandle(), entry.queueIdentifier(), entry.attempts());
        // Read BEFORE the ack: the worker may finish between the two, and an
        // operator who is told "nothing was running" because the race went the
        // other way has been told the one thing this flag exists to deny.
        boolean wasMediating = mediating(s, messageId).isPresent();
        boolean brokerAcked = consumer.get().ack(ackTarget);
        s.tracker().remove(entry.messageId());
        long elapsedMs = entry.elapsedSeconds(Instant.now()) * 1000;
        ctx.json(new ForceAckResponse(messageId, true, brokerAcked,
                brokerAcked ? null : BROKER_ACK_NOT_CONFIRMED, entry.queueIdentifier(), entry.poolCode(), elapsedMs,
                wasMediating));
    }

    // ── Queue depth / broker stats / traffic ─────────────────────────────

    /// `GET /monitoring/queues` — the latest broker-side depth per queue.
    ///
    /// **snake_case, alone on this surface.** Its neighbours are camelCase;
    /// this one is not, because that is the shape the dashboard already parses.
    /// Tidying it would be a wire break dressed up as consistency.
    ///
    /// Sorted by queue id. The cache hands back an unordered map, and a list
    /// whose rows move between two polls of the same unchanged data is a
    /// dashboard nobody can read.
    private static void queues(Context ctx, State s) {
        if (s.brokerStats() == null) {
            ctx.json(List.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        ctx.json(s.brokerStats().latest().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new QueueMetricsView(e.getKey(), e.getValue().pending(), e.getValue().inFlight()))
                .toList());
    }

    /// `GET /monitoring/queue-stats` — per-queue counters, keyed by queue.
    ///
    /// `time_window=5min|30min` narrows the counters to that window (the cache
    /// keeps 30 minutes of snapshots); anything else is all-time.
    /// `refresh=true` samples the brokers before rendering, for an operator who
    /// would otherwise wait out the housekeeping tick.
    ///
    /// The derivation worth stating: `successRate` is **1.0 when nothing has
    /// been processed**, not 0.0. A queue that has done nothing has failed
    /// nothing, and zero would paint every freshly-created queue as a total
    /// outage on the dashboard.
    private static void queueStats(Context ctx, State s) {
        if (s.brokerStats() == null) {
            ctx.json(Map.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        if ("true".equalsIgnoreCase(queryParam(ctx, "refresh"))) {
            refreshBrokerStats(s);
        }
        var window = parseTimeWindow(ctx.queryParam("time_window"));
        Map<String, DashboardQueueStats> out = new LinkedHashMap<>();
        s.brokerStats().windowed(window).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), queueStatsRow(e.getKey(), e.getValue())));
        ctx.json(out);
    }

    /// One `queue-stats` row. Separated from the HTTP so the derivations can be
    /// tested on values chosen to make a wrong one visible.
    static DashboardQueueStats queueStatsRow(String queue, io.flowcatalyst.router.queue.QueueMetrics m) {
        long processed = m.acked() + m.nacked();
        // 1.0, not 0.0 — see the handler's javadoc.
        double successRate = processed > 0 ? (double) m.acked() / processed : 1.0;
        return new DashboardQueueStats(queue, m.polled(), m.acked(), m.nacked(),
                DEFERRALS_ARE_NACKS, successRate, m.pending() + m.inFlight(),
                THROUGHPUT_NOT_COMPUTED, m.pending(), m.inFlight());
    }

    /// `POST /monitoring/broker-stats/refresh` — sample now rather than waiting
    /// for the housekeeping tick.
    ///
    /// `ageSeconds` is clamped at zero: [BrokerStatsCache#ageSeconds] answers
    /// [BrokerStatsCache#NEVER_REFRESHED] before the first sample, and a `-1`
    /// on the response to a refresh that just happened would be nonsense.
    private static void brokerStatsRefresh(Context ctx, State s) {
        if (s.brokerStats() == null) {
            serviceUnavailable(ctx, "broker stats not configured");
            return;
        }
        refreshBrokerStats(s);
        ctx.json(new BrokerStatsRefreshResponse(true, Math.max(0, s.brokerStats().ageSeconds())));
    }

    /// Samples the same queues the housekeeping loop does, by asking the
    /// manager for its sources rather than keeping a second list here — one
    /// that would quietly stop matching after the first reconfigure.
    private static void refreshBrokerStats(State s) {
        s.brokerStats().refresh(s.manager() == null ? Map.of() : s.manager().queueMetricSources());
    }

    /// `GET /monitoring/traffic-status`.
    ///
    /// `lastError` is the field that earns this endpoint. It is reported
    /// separately from `registered` because a **failed deregister** leaves the
    /// router believing it is out of the balancer while the balancer is still
    /// sending it traffic — the two facts disagree, and an operator deciding
    /// whether it is safe to stop the process needs both.
    private static void trafficStatus(Context ctx, State s) {
        var status = s.traffic() == null ? Traffic.Status.disabled() : s.traffic().status();
        ctx.json(new TrafficStatusResponse(status.enabled(), status.mode(),
                status.targetGroupArn().orElse(null), status.registered(),
                status.lastChange().orElse(null), status.lastError().orElse(null)));
    }

    // ── In-flight detail ─────────────────────────────────────────────────

    /// `GET /monitoring/in-flight-messages/detail` — everything known about one
    /// tracked message.
    ///
    /// An unknown id is **not a 404**: `inPipeline:false` is the answer to
    /// "is it safe to resend this?", and the caller asking is usually asking
    /// precisely because it expects the answer to be no.
    private static void inFlightDetail(Context ctx, State s) {
        String messageId = queryParam(ctx, "messageId");
        InFlightMessage entry = null;
        for (var im : s.tracker().snapshot()) {
            if (im.messageId().equals(messageId)) {
                entry = im;
                break;
            }
        }
        if (entry == null) {
            ctx.json(InFlightMessageDetail.notInPipeline(messageId));
            return;
        }
        ctx.json(inFlightDetail(entry, mediating(s, messageId).orElse(null), Instant.now()));
    }

    /// The tracker entry joined to the live worker view, separated from the
    /// HTTP so each status can be produced from state chosen to distinguish it.
    static InFlightMessageDetail inFlightDetail(InFlightMessage entry,
                                                io.flowcatalyst.router.pool.Mediating mediating, Instant now) {
        // Order matters: being inside a worker is the strongest fact, and a
        // message on its second attempt IS in a worker while it is being
        // retried — reporting RETRY_BACKOFF there would tell an operator it is
        // waiting when it is actually stuck against the target.
        String status = mediating != null ? "MEDIATING"
                : entry.retrying() ? "RETRY_BACKOFF"
                : "TRACKED_IDLE";
        return new InFlightMessageDetail(entry.messageId(), true, status,
                emptyToNull(entry.brokerMessageId()), entry.queueIdentifier(), entry.poolCode(),
                emptyToNull(entry.messageGroupId()), entry.attempts(),
                millisBetween(entry.startedAt(), now), entry.startedAt(),
                entry.lastSeenAt(), millisBetween(entry.lastSeenAt(), now),
                mediating == null ? null : mediating.target(),
                mediating == null ? null : millisBetween(mediating.startedAt(), now));
    }

    /// The live worker entry for a message, if any pool has one.
    private static java.util.Optional<io.flowcatalyst.router.pool.Mediating> mediating(State s, String messageId) {
        if (s.manager() == null) {
            return java.util.Optional.empty();
        }
        return s.manager().pools().values().stream()
                .flatMap(pool -> pool.mediating().stream())
                .filter(row -> row.messageId().equals(messageId))
                .findFirst();
    }

    /// Never negative: a clock that stepped backwards should read as "just
    /// now", not as a message delivered in the future.
    private static long millisBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis());
    }

    /// `""` → `null`, so Jackson drops the field the way Go's `omitempty`
    /// does on these two.
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    // ── Pool update ──────────────────────────────────────────────────────

    private static void updatePool(Context ctx, State s) {
        if (s.manager() == null) {
            serviceUnavailable(ctx, "pool updater not configured");
            return;
        }
        String poolCode = ctx.pathParam("poolCode");
        var req = ctx.bodyAsClass(PoolConfigUpdateRequest.class);
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
            notFound(ctx, "pool not found or update rejected: " + poolCode);
            return;
        }
        ctx.json(new PoolConfigUpdateResponse(true, poolCode,
                new PoolConfigUpdateNewConfig(req.concurrency(), req.rateLimitPerMinute())));
    }

    // ── Standby / stream health / config ────────────────────────────────

    private static void standbyStatus(Context ctx, State s) {
        if (s.election() == null) {
            ctx.json(new StandbyStatusResponse(false, true, "default"));
            return;
        }
        var cfg = s.electionConfig();
        boolean enabled = cfg != null && cfg.enabled();
        String instanceId = cfg != null && cfg.lockKey() != null && !cfg.lockKey().isBlank()
                ? cfg.lockKey() : "default";
        ctx.json(new StandbyStatusResponse(enabled, s.election().isLeader(), instanceId));
    }

    /// No stream processor provider is wired anywhere in this build, so this
    /// is always the documented no-provider fallback (spec §9.1 note; Go
    /// `handlers_misc.go: streamHealth`, nil-provider branch).
    private static void streamHealth(Context ctx, State s) {
        ctx.json(new StreamHealthResponse(false, "NOT_CONFIGURED", "no stream processor configured in this build"));
    }

    private static void streamProbe(Context ctx) {
        ctx.json(new StreamProbeResponse("NOT_CONFIGURED"));
    }

    private static void localConfig(Context ctx, State s) {
        ctx.json(new LocalConfigResponse(s.version(), s.warnings().count(), s.warnings().critical().size()));
    }

    /// No config source / reload mechanism is ported yet, so this always
    /// answers the Go "no explicit Reloader wired" branch verbatim (200, not
    /// 503, so a dashboard reload button keeps working) rather than a 501 —
    /// `handlers_misc.go: configReload`, nil-`Reloader` case.
    private static void configReload(Context ctx) {
        ctx.json(new ConfigReloadResponse(true, "config watcher polls automatically"));
    }

    // ── Dev mock targets ──────────────────────────────────────────────────

    private static void testFast(Context ctx, State s) {
        s.mocks().fast.incrementAndGet();
        ctx.json(new MockOkResponse(true, "fast"));
    }

    private static void testSuccess(Context ctx, State s) {
        s.mocks().success.incrementAndGet();
        ctx.json(new MockOkResponse(true, "success"));
    }

    private static void testSlow(Context ctx, State s) {
        s.mocks().slow.incrementAndGet();
        long delayMs = queryInt(ctx, "delay_ms", 0);
        if (delayMs <= 0 || delayMs > 30_000) {
            delayMs = 500;
        }
        sleep(delayMs);
        ctx.json(new MockOkResponse(true, "slow"));
    }

    private static void testFaulty(Context ctx, State s) {
        s.mocks().faulty.incrementAndGet();
        if (ThreadLocalRandom.current().nextInt(2) == 0) {
            s.mocks().faultyFail.incrementAndGet();
            ctx.status(500).json(new ErrorBody("faulty endpoint randomly failed"));
            return;
        }
        s.mocks().faultySuccess.incrementAndGet();
        ctx.json(new MockOkResponse(true, "faulty"));
    }

    private static void testFail(Context ctx, State s) {
        s.mocks().fail.incrementAndGet();
        ctx.status(500).json(new ErrorBody("test/fail"));
    }

    private static void testServerError(Context ctx, State s) {
        s.mocks().serverError.incrementAndGet();
        ctx.status(500).json(new ErrorBody("test/server-error"));
    }

    private static void testClientError(Context ctx, State s) {
        s.mocks().clientError.incrementAndGet();
        ctx.status(400).json(new ErrorBody("test/client-error"));
    }

    private static void testPending(Context ctx, State s) {
        s.mocks().pending.incrementAndGet();
        sleep(30_000);
        ctx.json(new MockOkResponse(true, "pending"));
    }

    private static void testStats(Context ctx, State s) {
        var m = s.mocks();
        ctx.json(new MockStatsResponse(m.fast.get(), m.slow.get(), m.faulty.get(), m.faultySuccess.get(),
                m.faultyFail.get(), m.fail.get(), m.success.get(), m.pending.get(), m.clientError.get(),
                m.serverError.get()));
    }

    private static void testStatsReset(Context ctx, State s) {
        s.mocks().reset();
        ctx.json(new ResetResponse(true));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Small helpers ────────────────────────────────────────────────────

    private static String queryParam(Context ctx, String name) {
        var v = ctx.queryParam(name);
        return v == null ? "" : v;
    }

    private static int queryInt(Context ctx, String name, int def) {
        var v = ctx.queryParam(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /// Status codes are the spec's contract; the error body shape is not
    /// pinned by §9.1 beyond that, so a minimal envelope is used here rather
    /// than the platform's lockfile-driven [io.flowcatalyst.platform.shared.httperror.HttpError],
    /// which belongs to the `/api/**` lockfile surface, not this legacy router API.
    private static void notFound(Context ctx, String message) {
        ctx.status(404).json(new ErrorBody(message));
    }

    private static void serviceUnavailable(Context ctx, String message) {
        ctx.status(503).json(new ErrorBody(message));
    }

    private record ErrorBody(String error) {
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────

    public record ProbeResponse(String status) {
    }

    public record SimpleHealthResponse(String status, String version,
                                       @JsonProperty("active_warnings") int activeWarnings,
                                       @JsonProperty("critical_warnings") int criticalWarnings) {
    }

    public record DashboardHealthResponse(String status, Instant timestamp, long uptimeMillis,
                                          DashboardHealthDetails details) {
    }

    public record DashboardHealthDetails(int totalQueues, int healthyQueues, int totalPools, int healthyPools,
                                         int activeWarnings, int criticalWarnings, int circuitBreakersOpen,
                                         String degradationReason) {
    }

    public record ConsumerHealthResponse(long currentTimeMs, Instant currentTime, Map<String, Object> consumers) {
    }

    /// `GET /monitoring`. Snake outer + nested `health_report`/`pool_stats`.
    public record MonitoringResponse(String status, String version,
                                     @JsonProperty("health_report") WireHealthReport healthReport,
                                     @JsonProperty("pool_stats") List<WirePoolStats> poolStats,
                                     @JsonProperty("active_warnings") int activeWarnings,
                                     @JsonProperty("critical_warnings") int criticalWarnings) {
    }

    public record WireHealthReport(String status, @JsonProperty("pools_healthy") int poolsHealthy,
                                   @JsonProperty("pools_unhealthy") int poolsUnhealthy,
                                   @JsonProperty("consumers_healthy") int consumersHealthy,
                                   @JsonProperty("consumers_unhealthy") int consumersUnhealthy,
                                   @JsonProperty("active_warnings") int activeWarnings,
                                   @JsonProperty("critical_warnings") int criticalWarnings, List<String> issues) {
    }

    /// One pool's stats for `GET /monitoring`/`GET /monitoring/pools`: snake
    /// outer fields, camelCase `metrics` (the real [PoolMetricsCollector.Snapshot]
    /// shape, including the Java-only `totalSuppressed`/`suppressedCount`
    /// counters `PoolMetricsCollector`'s own javadoc documents as a
    /// deliberate addition over the Go shape).
    public record WirePoolStats(@JsonProperty("pool_code") String poolCode, int concurrency,
                                @JsonProperty("active_workers") int activeWorkers,
                                @JsonProperty("queue_size") int queueSize,
                                @JsonProperty("queue_capacity") int queueCapacity,
                                @JsonProperty("message_group_count") int messageGroupCount,
                                @JsonProperty("rate_limit_per_minute") Integer rateLimitPerMinute,
                                @JsonProperty("is_rate_limited") boolean isRateLimited,
                                PoolMetricsCollector.Snapshot metrics) {
    }

    /// `GET /monitoring/pool-stats` map value — camelCase throughout, and
    /// distinct from [WirePoolStats]: no `isRateLimited`, but `totalRateLimited`
    /// and `availablePermits` instead.
    public record DashboardPoolStats(String poolCode, long totalProcessed, long totalSucceeded, long totalFailed,
                                     long totalRateLimited, double successRate, int activeWorkers,
                                     int availablePermits, int maxConcurrency, int queueSize, int maxQueueCapacity,
                                     double averageProcessingTimeMs) {
    }

    public record WireWarning(String id, String category, String severity, String message, String source,
                              @JsonProperty("created_at") Instant createdAt, boolean acknowledged,
                              @JsonProperty("acknowledged_at") Instant acknowledgedAt) {
    }

    public record AcknowledgedResponse(boolean acknowledged) {
    }

    public record AcknowledgedCountResponse(long acknowledged) {
    }

    public record DashboardCircuitBreaker(String name, String state, long successfulCalls, long failedCalls,
                                          long rejectedCalls, double failureRate, long bufferedCalls,
                                          long bufferSize) {
    }

    public record CircuitBreakerStateResponse(String name, String state, long successes, long failures,
                                              int recentFailures) {
    }

    public record BreakerResetResponse(boolean reset, String name) {
    }

    public record BreakerResetAllResponse(int reset) {
    }

    public record InFlightMessageInfo(String messageId, String brokerMessageId, String queueId, String poolCode,
                                      long elapsedTimeMs, Instant addedToInPipelineAt, String messageGroup,
                                      int attempts) {
    }

    public record InFlightCheckResponse(String messageId, boolean inPipeline, String poolCode, String queueId) {
    }

    /// `GET /monitoring/in-flight-messages/detail`.
    ///
    /// **Deliberate deviation from Go**: Go marks `attempts` and the three
    /// millisecond fields `omitempty`, so a message on its first attempt
    /// reports no `attempts` field at all — indistinguishable from an
    /// endpoint that did not look. Here every field is present once
    /// `inPipeline` is true, because `attempts: 0` is the fact that separates
    /// a message pinned on its first delivery from one legitimately retrying,
    /// and that distinction is the whole point of the row. Absence is still
    /// used where it means something: a message that is not in the pipeline
    /// carries `messageId` and `inPipeline` and nothing else, and
    /// `mediationTarget`/`mediatingElapsedMs` appear only for `MEDIATING`.
    ///
    /// @param status             `MEDIATING` | `RETRY_BACKOFF` | `TRACKED_IDLE`,
    ///                           absent when not in the pipeline
    /// @param lastSeenAt         refreshed on every broker redelivery of the
    ///                           owner copy
    /// @param lastSeenElapsedMs  the phantom signature: a `TRACKED_IDLE` entry
    ///                           whose value keeps growing is one the broker
    ///                           has stopped redelivering, and it will
    ///                           ACK-swallow every requeued copy until cleared
    public record InFlightMessageDetail(String messageId, boolean inPipeline, String status,
                                        String brokerMessageId, String queueId, String poolCode,
                                        String messageGroup, Integer attempts, Long elapsedTimeMs,
                                        Instant addedToInPipelineAt, Instant lastSeenAt, Long lastSeenElapsedMs,
                                        String mediationTarget, Long mediatingElapsedMs) {

        static InFlightMessageDetail notInPipeline(String messageId) {
            return new InFlightMessageDetail(messageId, false, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }
    }

    /// `GET /monitoring/queues` — **snake_case**, alone on this surface,
    /// because that is the shape already on the wire.
    public record QueueMetricsView(@JsonProperty("queue_identifier") String queueIdentifier,
                                   @JsonProperty("pending_messages") long pendingMessages,
                                   @JsonProperty("in_flight_messages") long inFlightMessages) {
    }

    /// `GET /monitoring/queue-stats` map value — camelCase.
    ///
    /// `currentSize` is `pendingMessages + inFlightMessages`, and
    /// `messagesNotVisible` is `inFlightMessages` under the SQS name the
    /// dashboard uses; both are kept as separate fields because that is what
    /// the wire contract says, not because they are separate facts.
    public record DashboardQueueStats(String name, long totalMessages, long totalConsumed, long totalFailed,
                                      long totalDeferred, double successRate, long currentSize, double throughput,
                                      long pendingMessages, long messagesNotVisible) {
    }

    public record BrokerStatsRefreshResponse(boolean refreshed, long ageSeconds) {
    }

    /// `GET /monitoring/traffic-status`.
    ///
    /// `lastChangedAt` is an [Instant] rather than Go's hand-formatted
    /// millisecond string: every other timestamp on this surface goes through
    /// the platform's one RFC 3339 layout, and one layout across the API beats
    /// reproducing the single place Go rolled its own. Still RFC 3339, still
    /// parses.
    public record TrafficStatusResponse(boolean enabled, String mode, String targetGroupArn, boolean registered,
                                        Instant lastChangedAt, String lastError) {
    }

    public record InFlightCheckBatchRequest(List<String> messageIds) {
        public InFlightCheckBatchRequest {
            messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        }
    }

    public record ForceAckResponse(String messageId, boolean removed, boolean brokerAcked, String brokerAckError,
                                   String queueId, String poolCode, long elapsedTimeMs, boolean wasMediating) {
    }

    public record PoolConfigUpdateRequest(Integer concurrency,
                                          @JsonProperty("rate_limit_per_minute") Integer rateLimitPerMinute) {
    }

    public record PoolConfigUpdateResponse(boolean success, @JsonProperty("pool_code") String poolCode,
                                           @JsonProperty("new_config") PoolConfigUpdateNewConfig newConfig) {
    }

    public record PoolConfigUpdateNewConfig(Integer concurrency,
                                            @JsonProperty("rate_limit_per_minute") Integer rateLimitPerMinute) {
    }

    public record StandbyStatusResponse(boolean enabled, @JsonProperty("is_leader") boolean isLeader,
                                        @JsonProperty("instance_id") String instanceId) {
    }

    public record StreamHealthResponse(boolean enabled, String status, String detail) {
    }

    public record StreamProbeResponse(String status) {
    }

    public record LocalConfigResponse(String version, @JsonProperty("warnings_total") long warningsTotal,
                                      @JsonProperty("warnings_critical") long warningsCritical) {
    }

    public record ConfigReloadResponse(boolean success, String note) {
    }

    public record MockOkResponse(boolean ok, String endpoint) {
    }

    public record MockStatsResponse(long fast, long slow, long faulty,
                                    @JsonProperty("faulty_success") long faultySuccess,
                                    @JsonProperty("faulty_fail") long faultyFail, long fail, long success,
                                    long pending, @JsonProperty("client_error") long clientError,
                                    @JsonProperty("server_error") long serverError) {
    }

    public record ResetResponse(boolean reset) {
    }
}

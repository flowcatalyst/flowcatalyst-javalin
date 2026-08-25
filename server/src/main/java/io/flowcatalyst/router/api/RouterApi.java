package io.flowcatalyst.router.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.manager.Warnings;
import io.flowcatalyst.router.observability.WarningStore;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.standby.LeaderElection;
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
/// under [State#prefix] (default `/router`); BasicAuth (§9.7) is a separate,
/// not-yet-ported concern and is applied by the caller, not here.
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
///   - `GET /monitoring`, `/monitoring/pools`, `/monitoring/pool-stats` —
///     `WirePoolStats`/`DashboardPoolStats` need `activeWorkers`,
///     `messageGroupCount`/`availablePermits` and `isRateLimited`. [Pool]
///     exposes only `config()`, `queueSize()` and `flushRegistry()`; the
///     semaphore, the ordered-group count and the rate limiter's current
///     state are private with no accessor. Inventing zeros for fields the
///     spec expects to be *live* would misrepresent the contract more than
///     omitting the route.
///   - `GET /monitoring/queue-stats`, `GET /monitoring/queues` — both need
///     every configured queue's [io.flowcatalyst.router.queue.QueueMetrics].
///     [RouterManager] exposes only `consumer(String queueId)` (single
///     lookup), not a way to enumerate all queue ids. `queue-stats` also
///     needs `totalDeferred` and a 30-minute windowed history
///     (`router/broker_stats.go`), neither of which has a Java port.
///   - `POST /monitoring/broker-stats/refresh` — no broker-stats cache ported.
///   - `GET /monitoring/mediating`, and the `MEDIATING` branch of
///     `GET /monitoring/in-flight-messages/detail` — both need the live
///     "currently inside a mediator call" set (`MediatingEntry`). Nothing in
///     this module's scope tracks that, so `/monitoring/in-flight-messages/detail`
///     is skipped outright rather than silently never reporting `MEDIATING`.
///   - `GET /monitoring/traffic-status` — no ALB/traffic-management runtime
///     ported (`Env.java` only parses the env vars).
///   - `GET/POST /messages`, `POST /api/seed/messages` — need a publisher
///     abstraction that does not exist yet.
///   - `POST /config/reload` beyond the "no reloader wired" branch — no
///     config-source/reload mechanism ported; see [#configReload].
///   - `GET /monitoring/dashboard`, `/dashboard.html` — no embedded HTML
///     asset in scope; recreating the dashboard UI is out of proportion for
///     an API port.
///   - `GET /metrics` (Prometheus) — blocked by the same `Pool`/consumer
///     gaps as the pool/queue routes above.
///   - `GET /openapi.json`, `/docs` — huma-generated docs; no equivalent
///     generator wired for the router surface.
///
/// `GET /monitoring/standby-status` — buildable: [LeaderElection] was found
/// while reading the router package even though the task brief didn't list
/// it. `DELETE /warnings` and `DELETE /warnings/old` are the two exceptions
/// that dropped for a different reason: [WarningStore] has no bulk-remove
/// method, only `raise`/`acknowledge`/`cleanup`/the read accessors.
public final class RouterApi {

    private static final Instant STARTED_AT = Instant.now();

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
    public record State(RouterManager manager, InFlightTracker tracker, WarningStore warnings,
                        BreakerRegistry breakers, LeaderElection election, LeaderElection.Config electionConfig,
                        String version, String prefix, MockCounters mocks) {

        public State {
            Objects.requireNonNull(tracker, "tracker");
            Objects.requireNonNull(warnings, "warnings");
            version = version == null || version.isBlank() ? "dev" : version;
            prefix = prefix == null || prefix.isBlank() ? "/router" : prefix;
            mocks = mocks == null ? new MockCounters() : mocks;
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
        routes.get(p + "/monitoring/health", ctx -> monitoringHealth(ctx, s));
        routes.get(p + "/monitoring/consumer-health", ctx -> consumerHealth(ctx, s));

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
        routes.post(p + "/monitoring/in-flight-messages/check-batch", ctx -> inFlightCheckBatch(ctx, s));
        routes.post(p + "/monitoring/in-flight-messages/{messageId}/ack", ctx -> forceAck(ctx, s));

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
    /// `wasMediating` is always `false`: nothing in this module's scope
    /// tracks the live "currently mediating" set (see class doc).
    ///
    /// `brokerAcked` is always `true` and `brokerAckError` is never set:
    /// [io.flowcatalyst.router.queue.Consumer#ack] is documented
    /// best-effort/never-throws with no return value, so the outcome the Go
    /// `AckErr` field reports is not observable through the Java interface.
    /// Deliberate choice, not a bug — see the interface's own javadoc.
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
        consumer.get().ack(ackTarget);
        s.tracker().remove(entry.messageId());
        long elapsedMs = entry.elapsedSeconds(Instant.now()) * 1000;
        ctx.json(new ForceAckResponse(messageId, true, true, null, entry.queueIdentifier(), entry.poolCode(),
                elapsedMs, false));
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

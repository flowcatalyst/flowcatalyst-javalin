package io.flowcatalyst.router.api;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.lifecycle.BrokerStatsCache;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.observability.WarningStore;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.standby.LeaderElection;
import io.flowcatalyst.router.traffic.Traffic;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/// The router's monitoring HTTP API (`docs/spec/router.md` §9.1). Mounted
/// under [State#prefix] (default `/router`). BasicAuth (§9.7) guards this
/// surface and is applied by the caller — see
/// `io.flowcatalyst.router.api.auth.BasicAuthFilter`, which strips the mount
/// prefix before deciding whether a path is public.
///
/// ### Where the handlers live
///
/// This class is the mount point and the shared [State]; the handlers are
/// grouped by resource, one file each, and every group registers its own
/// routes so a path and the code behind it are never far apart:
///
///   - [HealthRoutes] — `/health*`, `/monitoring`, `/monitoring/health`,
///     `/monitoring/consumer-health`
///   - [PoolRoutes] — `/monitoring/pools`, `/pool-stats`, `/mediating`, and
///     the `PUT` hot-update
///   - [WarningRoutes] — both warning surfaces
///   - [BreakerRoutes] — circuit-breaker reads and resets
///   - [GroupFlushRoutes] — group-flush suppression list and operator
///     clear (R-52, R-53)
///   - [InFlightRoutes] — what this process owns, plus the force-ACK override
///   - [QueueRoutes] — broker-side depth, forced sampling, traffic status
///   - [AdminRoutes] — standby, stream health, config snapshot
///   - [MockRoutes] — the dev mock targets
///
/// [Wire] holds every shape that goes on the wire and [Http] the request
/// reading and error responses they share.
///
/// ### Routes deliberately absent
///
/// Every route needs a live data source. These have none, and are missing
/// rather than faked:
///
///   - `GET/POST /messages`, `POST /api/seed/messages` — need a publisher
///     abstraction that does not exist yet.
///   - `POST /config/reload` beyond the "no reloader wired" branch — no
///     config-source/reload mechanism ported; see [AdminRoutes].
///   - `GET /monitoring/dashboard`, `/dashboard.html` — served by
///     `io.flowcatalyst.router.api.dashboard.DashboardHandler`, which is a
///     consumer of this API rather than part of it.
///   - `GET /metrics` (Prometheus) — rendered by
///     `io.flowcatalyst.router.prometheus.RouterPrometheusCollector`; only
///     the alias under this prefix is still to be mounted.
///   - `GET /openapi.json`, `/docs` — huma-generated docs; no equivalent
///     generator wired for the router surface.
///   - `DELETE /warnings`, `DELETE /warnings/old` — see [WarningRoutes].
public final class RouterApi {

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
    /// @param electionConfig the election's static config, read only for
    ///                       `enabled` — `/monitoring/standby-status`'s
    ///                       `instance_id` is [LeaderElection#instanceId],
    ///                       the per-process id, never `electionConfig`'s
    ///                       shared lock key (R-56)
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

    /// Mounts every route this module can honestly serve, by asking each
    /// group to mount its own. See the class doc for what is deliberately
    /// absent.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        HealthRoutes.register(routes, s);
        PoolRoutes.register(routes, s);
        WarningRoutes.register(routes, s);
        BreakerRoutes.register(routes, s);
        GroupFlushRoutes.register(routes, s);
        InFlightRoutes.register(routes, s);
        QueueRoutes.register(routes, s);
        AdminRoutes.register(routes, s);
        MockRoutes.register(routes, s);
    }
}

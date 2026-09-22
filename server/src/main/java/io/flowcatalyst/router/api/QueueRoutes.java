package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.lifecycle.BrokerStatsCache;
import io.flowcatalyst.router.traffic.Traffic;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Broker-side queue depth and load-balancer registration (§9.1).
///
/// Everything here reads [BrokerStatsCache], which is sampled on the
/// housekeeping loop rather than per request — a dashboard tab must not turn
/// into steady broker load. The trap that follows from it: a queue the cache
/// could not sample **keeps its previous reading** rather than reporting zero
/// depth, so a row can be stale during an outage. That is the honest failure,
/// and `ageSeconds` on the refresh response is how stale.
final class QueueRoutes {

    /// `queue-stats`' `throughput`. Go hard-codes 0.0 — it never computed a
    /// rate — and a plausible-looking number invented here would be worse than
    /// an obviously absent one, because a dashboard would plot it.
    private static final double THROUGHPUT_NOT_COMPUTED = 0.0;

    /// Mounts this group. Called by [RouterApi#register].
    static void register(Routes routes, State s) {
        var p = s.prefix();
        routes.get(p + "/monitoring/queues", ctx -> queues(ctx, s));
        routes.get(p + "/monitoring/queue-stats", ctx -> queueStats(ctx, s));
        routes.post(p + "/monitoring/broker-stats/refresh", ctx -> brokerStatsRefresh(ctx, s));
        routes.get(p + "/monitoring/traffic-status", ctx -> trafficStatus(ctx, s));
    }

    /// `GET /monitoring/queues` — the latest broker-side depth per queue.
    ///
    /// **snake_case, alone on this surface.** Its neighbours are camelCase;
    /// this one is not, because that is the shape the dashboard already parses.
    /// Tidying it would be a wire break dressed up as consistency.
    ///
    /// Sorted by queue id. The cache hands back an unordered map, and a list
    /// whose rows move between two polls of the same unchanged data is a
    /// dashboard nobody can read.
    private static void queues(Exchange ctx, State s) {
        if (s.brokerStats() == null) {
            ctx.json(List.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        ctx.json(s.brokerStats().latest().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new Wire.QueueMetricsView(e.getKey(), e.getValue().pending(), e.getValue().inFlight()))
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
    private static void queueStats(Exchange ctx, State s) {
        if (s.brokerStats() == null) {
            ctx.json(Map.of()); // empty payload for lists (spec §9.1 note)
            return;
        }
        if ("true".equalsIgnoreCase(Http.queryParam(ctx, "refresh"))) {
            refreshBrokerStats(s);
        }
        var window = Http.parseTimeWindow(ctx.queryParam("time_window"));
        Map<String, Wire.DashboardQueueStats> out = new LinkedHashMap<>();
        s.brokerStats().windowed(window).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), queueStatsRow(e.getKey(), e.getValue())));
        ctx.json(out);
    }

    /// One `queue-stats` row. Separated from the HTTP so the derivations can be
    /// tested on values chosen to make a wrong one visible.
    ///
    /// `totalDeferred` used to be structurally zero on both sides: Go carries
    /// a `Defer` verb on every backend and the counter this field reports,
    /// but had no production caller either, and Java had grown no `defer`
    /// verb at all — a hand-back was a `nack` regardless of reason. Owner
    /// ruling 2026-09-22 (`docs/spec/router-hol-deferral.md` §2) gives every
    /// backend a real `defer`, counted apart from `nack`, so this now reports
    /// the consumer's own lifetime count.
    static Wire.DashboardQueueStats queueStatsRow(String queue, io.flowcatalyst.router.queue.QueueMetrics m) {
        long processed = m.acked() + m.nacked();
        // 1.0, not 0.0 — see the handler's javadoc.
        double successRate = processed > 0 ? (double) m.acked() / processed : 1.0;
        return new Wire.DashboardQueueStats(queue, m.polled(), m.acked(), m.nacked(),
                m.deferred(), successRate, m.pending() + m.inFlight(),
                THROUGHPUT_NOT_COMPUTED, m.pending(), m.inFlight());
    }

    /// `POST /monitoring/broker-stats/refresh` — sample now rather than waiting
    /// for the housekeeping tick.
    ///
    /// `ageSeconds` is clamped at zero: [BrokerStatsCache#ageSeconds] answers
    /// [BrokerStatsCache#NEVER_REFRESHED] before the first sample, and a `-1`
    /// on the response to a refresh that just happened would be nonsense.
    private static void brokerStatsRefresh(Exchange ctx, State s) {
        if (s.brokerStats() == null) {
            Http.serviceUnavailable(ctx, "broker stats not configured");
            return;
        }
        refreshBrokerStats(s);
        ctx.json(new Wire.BrokerStatsRefreshResponse(true, Math.max(0, s.brokerStats().ageSeconds())));
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
    private static void trafficStatus(Exchange ctx, State s) {
        var status = s.traffic() == null ? Traffic.Status.disabled() : s.traffic().status();
        ctx.json(new Wire.TrafficStatusResponse(status.enabled(), status.mode(),
                status.targetGroupArn().orElse(null), status.registered(),
                status.lastChange().orElse(null), status.lastError().orElse(null)));
    }

    private QueueRoutes() {
    }
}

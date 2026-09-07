package io.flowcatalyst.router.api;

import io.flowcatalyst.router.api.RouterApi.State;
import io.flowcatalyst.router.lifecycle.BrokerStatsCache;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.prometheus.RouterPrometheusCollector;
import io.flowcatalyst.router.prometheus.RouterPrometheusCollector.PoolSnapshot;
import io.flowcatalyst.router.prometheus.RouterPrometheusCollector.QueueSnapshot;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import io.prometheus.metrics.expositionformats.ExpositionFormats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/// The Prometheus alias under the router's own mount prefix (`docs/spec/router.md`
/// §1.4/§9.2: text exposition at `<prefix>/metrics` on the API port, next to
/// `/metrics` on the separate metrics port served by [io.flowcatalyst.server.Metrics]).
///
/// Renders the same [RouterPrometheusCollector] family set, through the same
/// [ExpositionFormats] negotiation [io.flowcatalyst.server.Metrics#scrape]
/// uses — content-type chosen from the request's `Accept` header, defaulting
/// to the classic text format. The collector itself is built fresh per
/// request from [State]'s already-live collaborators (no new subsystem, no
/// cached snapshot): [State#manager]'s pools + [State#poolMetrics] for the
/// pool families, [State#brokerStats] for the queue families (empty when
/// unwired, matching [RouterPrometheusCollector.QueueSource#NONE]),
/// [State#breakers] (an empty, never-populated registry when unwired — the
/// breaker families simply render no series, same shape as every other
/// "provider not configured" degradation in this API), and [State#tracker]
/// (always present).
///
/// [io.flowcatalyst.router.api.auth.BasicAuthFilter] already exempts
/// `/metrics` from credentials (§9.7); mounting under the prefix does not
/// change that — the filter strips the prefix before the public-path check.
final class MetricsRoutes {

    private static final ExpositionFormats FORMATS = ExpositionFormats.init();

    /// Built once and reused only as the harmless "no breaker" fallback
    /// (never populated, so its [BreakerRegistry#snapshot] is always empty)
    /// — [RouterPrometheusCollector] requires a non-null [BreakerRegistry],
    /// and `null` here means the same "not configured" state every other
    /// route in this package degrades on.
    private static final BreakerRegistry NO_BREAKERS =
            new BreakerRegistry(io.flowcatalyst.router.policy.CircuitBreaker.Config.DEFAULTS, Clock.systemUTC());

    private MetricsRoutes() {
    }

    /// Mounts this group. Called by [RouterApi#register].
    static void register(Routes routes, State s) {
        routes.get(s.prefix() + "/metrics", ctx -> metrics(ctx, s));
    }

    private static void metrics(Exchange ctx, State s) {
        var collector = new RouterPrometheusCollector(poolSource(s), queueSource(s), breakers(s), s.tracker());
        var writer = FORMATS.findWriter(ctx.header("Accept"));
        var out = new ByteArrayOutputStream();
        try {
            writer.write(out, collector.collect());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ctx.contentType(writer.getContentType()).result(out.toByteArray());
    }

    private static RouterPrometheusCollector.PoolSource poolSource(State s) {
        if (s.manager() == null) {
            return RouterPrometheusCollector.PoolSource.NONE;
        }
        return () -> s.manager().pools().values().stream()
                .map(pool -> toPoolSnapshot(pool, s.poolMetrics()))
                .toList();
    }

    private static PoolSnapshot toPoolSnapshot(Pool pool, Map<String, PoolMetricsCollector> poolMetrics) {
        var code = pool.config().code();
        // A pool with no registered collector (Go's `if s.Metrics != nil`
        // guard, mirrored in PoolRoutes) still needs one to hand the
        // collector — an all-zero one it never records into.
        var metrics = poolMetrics.getOrDefault(code, new PoolMetricsCollector(Clock.systemUTC()));
        return new PoolSnapshot(code, pool.queueSize(), pool.activeWorkers(), pool.messageGroupCount(), metrics);
    }

    private static RouterPrometheusCollector.QueueSource queueSource(State s) {
        BrokerStatsCache cache = s.brokerStats();
        if (cache == null) {
            return RouterPrometheusCollector.QueueSource.NONE;
        }
        return () -> cache.latest().entrySet().stream()
                .map(e -> new QueueSnapshot(e.getKey(), e.getValue().pending(), e.getValue().inFlight(),
                        e.getValue().polled(), e.getValue().acked(), e.getValue().nacked(), 0))
                .toList();
    }

    private static BreakerRegistry breakers(State s) {
        return s.breakers() == null ? NO_BREAKERS : s.breakers();
    }
}

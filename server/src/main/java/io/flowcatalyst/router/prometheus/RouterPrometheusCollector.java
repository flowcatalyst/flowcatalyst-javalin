package io.flowcatalyst.router.prometheus;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.PoolMetricsCollector;
import io.flowcatalyst.router.policy.BreakerRegistry;
import io.flowcatalyst.router.policy.CircuitBreaker;

import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.ClassicHistogramBuckets;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/// Renders the router's metrics in Prometheus text-exposition format
/// (`docs/spec/router.md` §9.2, ported from `internal/router/api/prometheus.go`).
///
/// A fresh [MetricSnapshots] is built on every [#collect] — no process-level
/// default metrics, no background thread, no cached state. That mirrors Go's
/// `routerCollector`, whose `Collect` method reads `State` fresh on every
/// scrape via `promhttp.HandlerFor` (`prometheus.go:59-65`).
///
/// Data sources, matching the Go collector's `State` fields:
///   - [PoolSource] for the per-pool gauges plus each pool's
///     [PoolMetricsCollector] (`fc_pool_*`, `fc_messages_processed_total`,
///     `fc_rate_limit_exceeded_total`, `fc_messages_suppressed_total` (R-53),
///     `fc_mediation_duration_seconds`). Kept
///     as a small interface — not a direct dependency on
///     `io.flowcatalyst.router.pool.Pool` — because that class does not yet
///     expose active-worker / message-group counts and wiring it is a
///     different unit's job.
///   - [QueueSource] for the broker-stats-cache-shaped series
///     (`fc_queue_pending_messages`, `fc_queue_in_flight_messages`,
///     `fc_consumer_messages_received_total`, `fc_queue_messages_total`) —
///     Go's `BrokerStats` (§9.3) is not yet ported to Java, so
///     [QueueSource#queues] returning an empty list renders none of these
///     series, exactly as Go does when `state.BrokerStats == nil`
///     (`prometheus.go:108-110`).
///   - [BreakerRegistry] for `fc_circuit_breaker_open` /
///     `fc_circuit_breaker_calls_total`.
///   - [InFlightTracker] for `fc_in_pipeline_messages`.
///
/// TODO(Q41): the established metrics contract additionally defines
/// `fc_messages_submitted_total`, `fc_messages_rejected_total{reason}`,
/// `fc_consumer_polls_total`, `fc_consumer_errors_total{type}`, a `result`
/// label on `fc_messages_processed_total`, and `flowcatalyst_broker_*`. Go's
/// pull-based collector does not emit them (comment at `prometheus.go:38-44`)
/// and neither does this port — reproducing the gap rather than silently
/// "fixing" it, per `docs/spec/router.md` §13 Q41 (unruled: keep the gap, or
/// emit the fuller set with event-time counters?).
public final class RouterPrometheusCollector implements MultiCollector {

    /// One pool's exposition-time gauges, mirroring a Go `PoolStat` entry
    /// (`metrics.go`'s `PoolStats()`, surfaced to the collector via
    /// `State.PoolStats`).
    public record PoolSnapshot(String poolCode, long queueSize, long activeWorkers,
                                long messageGroupCount, PoolMetricsCollector metrics) {

        public PoolSnapshot {
            Objects.requireNonNull(poolCode, "poolCode");
            Objects.requireNonNull(metrics, "metrics");
        }
    }

    /// All currently registered pools, read fresh on every [#pools] call.
    @FunctionalInterface
    public interface PoolSource {

        List<PoolSnapshot> pools();

        PoolSource NONE = List::of;
    }

    /// One queue/consumer's exposition-time counters, mirroring Go's
    /// `BrokerStats.GetWindowed(0)` entries (§9.3; not yet ported to Java).
    public record QueueSnapshot(String queueIdentifier, long pendingMessages, long inFlightMessages,
                                 long totalPolled, long totalAcked, long totalNacked, long totalDeferred) {

        public QueueSnapshot {
            Objects.requireNonNull(queueIdentifier, "queueIdentifier");
        }
    }

    /// All currently cached broker/queue stats, read fresh on every
    /// [#queues] call. [#NONE] renders none of the `fc_queue_*` /
    /// `fc_consumer_messages_received_total` series, matching Go's
    /// nil-`BrokerStats` behaviour.
    @FunctionalInterface
    public interface QueueSource {

        List<QueueSnapshot> queues();

        QueueSource NONE = List::of;
    }

    private static final ExpositionFormats FORMATS = ExpositionFormats.init();

    private final PoolSource poolSource;
    private final QueueSource queueSource;
    private final BreakerRegistry breakers;
    private final InFlightTracker inFlight;

    public RouterPrometheusCollector(PoolSource poolSource, QueueSource queueSource,
                                      BreakerRegistry breakers, InFlightTracker inFlight) {
        this.poolSource = Objects.requireNonNull(poolSource, "poolSource");
        this.queueSource = Objects.requireNonNull(queueSource, "queueSource");
        this.breakers = Objects.requireNonNull(breakers, "breakers");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
    }

    @Override
    public MetricSnapshots collect() {
        var pools = poolSource.pools();
        var queues = queueSource.queues();
        var breakerStats = breakers.snapshot();

        // Each pool's dashboard-shaped snapshot (totals + windows) and
        // histogram snapshot are read once here and threaded through, rather
        // than re-read per metric family below — `snapshot()` sorts a copy
        // of the rolling sample buffer, so calling it once per pool per
        // scrape (not once per family) keeps a scrape O(pools), not
        // O(pools x families).
        var totals = pools.stream()
                .collect(Collectors.toMap(PoolSnapshot::poolCode, p -> p.metrics().snapshot()));
        var histograms = pools.stream()
                .collect(Collectors.toMap(PoolSnapshot::poolCode, p -> p.metrics().histogramSnapshot()));

        var out = MetricSnapshots.builder();

        addPoolGauge(out, "fc_pool_queue_size",
                "Messages buffered in group queues awaiting dispatch.",
                pools, PoolSnapshot::queueSize);
        addPoolGauge(out, "fc_pool_active_workers",
                "Currently active worker threads per pool.",
                pools, PoolSnapshot::activeWorkers);
        addPoolGauge(out, "fc_pool_message_groups",
                "Distinct message groups currently holding buffered work.",
                pools, PoolSnapshot::messageGroupCount);

        addProcessedCounter(out, pools, totals);
        addRateLimitedCounter(out, pools, totals);
        addSuppressedCounter(out, pools, totals);
        addMediationHistogram(out, pools, histograms);

        addQueueGauges(out, queues);
        addConsumerCounter(out, queues);
        addQueueOutcomeCounter(out, queues);

        addBreakerOpenGauge(out, breakerStats);
        addBreakerCallsCounter(out, breakerStats);

        addInFlightGauge(out);

        return out.build();
    }

    /// The classic Prometheus text-exposition format for this collector's
    /// current [#collect] output — a convenience for tests and for any
    /// caller that wants text without going through a
    /// [io.prometheus.metrics.model.registry.PrometheusRegistry]. Uses the
    /// same [ExpositionFormats] as the platform's own `/metrics` listener
    /// (`io.flowcatalyst.server.Metrics`).
    public String renderText() {
        var out = new ByteArrayOutputStream();
        try {
            FORMATS.getPrometheusTextFormatWriter().write(out, collect());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void addPoolGauge(MetricSnapshots.Builder out, String name, String help,
                                      List<PoolSnapshot> pools, ToLongFunction<PoolSnapshot> extractor) {
        var builder = GaugeSnapshot.builder().name(name).help(help);
        for (var pool : pools) {
            builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                    .value(extractor.applyAsLong(pool))
                    .labels(Labels.of("pool", pool.poolCode()))
                    .build());
        }
        out.metricSnapshot(builder.build());
    }

    private static void addProcessedCounter(MetricSnapshots.Builder out, List<PoolSnapshot> pools,
                                             Map<String, PoolMetricsCollector.Snapshot> totals) {
        var builder = CounterSnapshot.builder()
                .name("fc_messages_processed_total")
                .help("Cumulative messages processed, by success.");
        for (var pool : pools) {
            var snap = totals.get(pool.poolCode());
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(snap.totalSuccess())
                    .labels(Labels.of("pool", pool.poolCode(), "success", "true"))
                    .build());
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(snap.totalFailure())
                    .labels(Labels.of("pool", pool.poolCode(), "success", "false"))
                    .build());
        }
        out.metricSnapshot(builder.build());
    }

    private static void addRateLimitedCounter(MetricSnapshots.Builder out, List<PoolSnapshot> pools,
                                               Map<String, PoolMetricsCollector.Snapshot> totals) {
        var builder = CounterSnapshot.builder()
                .name("fc_rate_limit_exceeded_total")
                .help("Cumulative rate-limit events.");
        for (var pool : pools) {
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(totals.get(pool.poolCode()).totalRateLimited())
                    .labels(Labels.of("pool", pool.poolCode()))
                    .build());
        }
        out.metricSnapshot(builder.build());
    }

    /// R-53: a suppressed ACK (§4.5 `flushGroup`) must record its own
    /// metric distinct from an ordinary success, so a heavily-flushed pool
    /// reads as busy-but-suppressed rather than idle. Sourced from
    /// [PoolMetricsCollector.Snapshot#totalSuppressed], the same total the
    /// dashboard's pool-stats view already reads.
    private static void addSuppressedCounter(MetricSnapshots.Builder out, List<PoolSnapshot> pools,
                                              Map<String, PoolMetricsCollector.Snapshot> totals) {
        var builder = CounterSnapshot.builder()
                .name("fc_messages_suppressed_total")
                .help("Cumulative messages ACKed because their group was suppressed by a target flushGroup.");
        for (var pool : pools) {
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(totals.get(pool.poolCode()).totalSuppressed())
                    .labels(Labels.of("pool", pool.poolCode()))
                    .build());
        }
        out.metricSnapshot(builder.build());
    }

    private static void addMediationHistogram(MetricSnapshots.Builder out, List<PoolSnapshot> pools,
                                               Map<String, PoolMetricsCollector.HistogramSnapshot> histograms) {
        var builder = HistogramSnapshot.builder()
                .name("fc_mediation_duration_seconds")
                .help("Mediation latency in seconds.");
        for (var pool : pools) {
            var hist = histograms.get(pool.poolCode());
            builder.dataPoint(HistogramSnapshot.HistogramDataPointSnapshot.builder()
                    .classicHistogramBuckets(toClassicBuckets(hist))
                    .sum(hist.sumSeconds())
                    .labels(Labels.of("pool", pool.poolCode()))
                    .build());
        }
        out.metricSnapshot(builder.build());
    }

    /// [PoolMetricsCollector.HistogramSnapshot] stores **cumulative** counts
    /// (`counts[i]` = observations `<= bounds[i]`, constant 44), but
    /// [ClassicHistogramBuckets#of] wants the **per-bucket** (non-cumulative)
    /// count for each boundary — the exposition writer re-accumulates them
    /// into the cumulative `_bucket{le=...}` lines itself. Converting
    /// cumulative counts straight through here would double-count every
    /// bucket after the first and corrupt the wire histogram, so this
    /// delta step is load-bearing, not cosmetic.
    ///
    /// `+Inf` must be an explicit final bucket (the library rejects a
    /// classic-bucket list without one); its per-bucket count is whatever
    /// the cumulative total leaves over the widest finite boundary —
    /// observations beyond constant 44's 10 s bound.
    private static ClassicHistogramBuckets toClassicBuckets(PoolMetricsCollector.HistogramSnapshot snapshot) {
        double[] finiteBounds = snapshot.bounds();
        long[] cumulative = snapshot.counts();
        int n = finiteBounds.length;

        double[] bounds = new double[n + 1];
        long[] deltas = new long[n + 1];
        System.arraycopy(finiteBounds, 0, bounds, 0, n);
        bounds[n] = Double.POSITIVE_INFINITY;

        long previous = 0;
        for (int i = 0; i < n; i++) {
            deltas[i] = cumulative[i] - previous;
            previous = cumulative[i];
        }
        deltas[n] = snapshot.count() - previous;

        return ClassicHistogramBuckets.of(bounds, deltas);
    }

    private static void addQueueGauges(MetricSnapshots.Builder out, List<QueueSnapshot> queues) {
        var pending = GaugeSnapshot.builder().name("fc_queue_pending_messages")
                .help("Approximate messages waiting on the broker.");
        var inFlightGauge = GaugeSnapshot.builder().name("fc_queue_in_flight_messages")
                .help("Approximate messages currently being processed by consumers.");
        for (var queue : queues) {
            var labels = Labels.of("queue", normaliseQueueId(queue.queueIdentifier()));
            pending.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                    .value(queue.pendingMessages()).labels(labels).build());
            inFlightGauge.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                    .value(queue.inFlightMessages()).labels(labels).build());
        }
        out.metricSnapshot(pending.build());
        out.metricSnapshot(inFlightGauge.build());
    }

    /// The `consumer` label value is the same normalised queue identifier as
    /// `fc_queue_*`'s `queue` label — Go loops the same `BrokerStats` entries
    /// for both (`prometheus.go:126-141`); there is no separate per-consumer
    /// identity in the cache.
    private static void addConsumerCounter(MetricSnapshots.Builder out, List<QueueSnapshot> queues) {
        var builder = CounterSnapshot.builder()
                .name("fc_consumer_messages_received_total")
                .help("Cumulative messages received from the broker by this consumer.");
        for (var queue : queues) {
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(queue.totalPolled())
                    .labels(Labels.of("consumer", normaliseQueueId(queue.queueIdentifier())))
                    .build());
        }
        out.metricSnapshot(builder.build());
    }

    private static void addQueueOutcomeCounter(MetricSnapshots.Builder out, List<QueueSnapshot> queues) {
        var builder = CounterSnapshot.builder()
                .name("fc_queue_messages_total")
                .help("Cumulative consumer ack/nack/defer outcomes.");
        for (var queue : queues) {
            var id = normaliseQueueId(queue.queueIdentifier());
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(queue.totalAcked()).labels(Labels.of("queue", id, "outcome", "acked")).build());
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(queue.totalNacked()).labels(Labels.of("queue", id, "outcome", "nacked")).build());
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(queue.totalDeferred()).labels(Labels.of("queue", id, "outcome", "deferred")).build());
        }
        out.metricSnapshot(builder.build());
    }

    private static void addBreakerOpenGauge(MetricSnapshots.Builder out, Map<String, CircuitBreaker.Stats> breakerStats) {
        var builder = GaugeSnapshot.builder()
                .name("fc_circuit_breaker_open")
                .help("1 when the breaker is OPEN, 0 otherwise.");
        breakerStats.forEach((target, stats) -> builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                .value(stats.state() == CircuitBreaker.State.OPEN ? 1 : 0)
                .labels(Labels.of("target", target))
                .build()));
        out.metricSnapshot(builder.build());
    }

    private static void addBreakerCallsCounter(MetricSnapshots.Builder out, Map<String, CircuitBreaker.Stats> breakerStats) {
        var builder = CounterSnapshot.builder()
                .name("fc_circuit_breaker_calls_total")
                .help("Cumulative breaker outcomes.");
        breakerStats.forEach((target, stats) -> {
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(stats.successes())
                    .labels(Labels.of("target", target, "outcome", "success"))
                    .build());
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .value(stats.failures())
                    .labels(Labels.of("target", target, "outcome", "failure"))
                    .build());
        });
        out.metricSnapshot(builder.build());
    }

    private void addInFlightGauge(MetricSnapshots.Builder out) {
        out.metricSnapshot(GaugeSnapshot.builder()
                .name("fc_in_pipeline_messages")
                .help("Total in-flight messages across all pools.")
                .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .value(inFlight.size())
                        .build())
                .build());
    }

    /// Trims an AWS SQS-style URL down to the queue name after the last
    /// `/`, so label cardinality stays bounded (`prometheus.go:167-173`):
    /// `…/my-queue` → `my-queue`. An id with no `/`, or one ending in `/`,
    /// is returned unchanged.
    private static String normaliseQueueId(String id) {
        int i = id.lastIndexOf('/');
        if (i >= 0 && i < id.length() - 1) {
            return id.substring(i + 1);
        }
        return id;
    }
}

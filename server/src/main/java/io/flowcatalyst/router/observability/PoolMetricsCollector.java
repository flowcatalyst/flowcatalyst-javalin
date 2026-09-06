package io.flowcatalyst.router.observability;

import io.flowcatalyst.router.pool.PoolMetrics;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/// Per-pool delivery metrics for the dashboard (`docs/spec/router.md` §2.10,
/// §9.2, constants 43-44).
///
/// Two views over the same deliveries are kept:
///
///   - **Rolling windows** (`last5Min`/`last30Min`, constant 43) for the
///     dashboard's recent-throughput view. These are wire-visible — the
///     5/30-minute boundaries are the spec, not an implementation detail —
///     so a sample must fall out of a window the instant it crosses the
///     boundary, not "eventually."
///   - **A cumulative mediation-latency histogram** (constant 44), monotonic
///     across the collector's lifetime, for the Prometheus
///     `fc_mediation_duration_seconds` series a future exporter reads via
///     [#histogramSnapshot].
///
/// Only [#recordSuccess], [#recordFailure] and [#recordTransient] contribute
/// latency; [#recordRateLimited] and [#recordSuppressed] are not delivery
/// attempts, so they only ever move a total counter and (for the windowed
/// counts) a timestamped event list — no duration to record.
public final class PoolMetricsCollector implements PoolMetrics {

    /// `MaxSamples` / `ShortWindow` / `LongWindow` (constant 43).
    public record Config(int maxSamples, Duration shortWindow, Duration longWindow) {

        public static final Config DEFAULTS =
                new Config(10_000, Duration.ofMinutes(5), Duration.ofMinutes(30));

        public Config {
            if (maxSamples <= 0) {
                throw new IllegalArgumentException("maxSamples must be positive");
            }
            if (shortWindow.isNegative() || shortWindow.isZero()) {
                throw new IllegalArgumentException("shortWindow must be positive");
            }
            if (longWindow.isNegative() || longWindow.isZero()) {
                throw new IllegalArgumentException("longWindow must be positive");
            }
            if (longWindow.compareTo(shortWindow) < 0) {
                throw new IllegalArgumentException("longWindow must be >= shortWindow");
            }
        }
    }

    /// Latency aggregates in milliseconds, with the percentiles the
    /// dashboard renders (`docs/spec/router.md` §2.10). The zero value is
    /// what an empty window/buffer reports.
    public record ProcessingTimeMetrics(double avgMs, long minMs, long maxMs,
                                         long p50Ms, long p95Ms, long p99Ms, long sampleCount) {

        public static final ProcessingTimeMetrics EMPTY =
                new ProcessingTimeMetrics(0, 0, 0, 0, 0, 0, 0);
    }

    /// One rolling window's throughput + latency slice.
    public record WindowedMetrics(long successCount, long failureCount, long rateLimitedCount,
                                   long suppressedCount, double successRate, double throughputPerSec,
                                   ProcessingTimeMetrics processingTime, Instant windowStart,
                                   long windowDurationSecs) {
    }

    /// The dashboard-shaped snapshot (`common.EnhancedPoolMetrics` in the
    /// Go). `totalSuppressed` has no Go counterpart — [PoolMetrics] added
    /// `recordSuppressed` from the start in Java (see its javadoc, §13 Q53),
    /// so the total is counted here rather than left as a blind spot.
    public record Snapshot(long totalSuccess, long totalFailure, long totalRateLimited,
                            long totalSuppressed, double successRate,
                            ProcessingTimeMetrics processingTime,
                            WindowedMetrics last5Min, WindowedMetrics last30Min) {
    }

    /// A cumulative-histogram snapshot, shaped for a Prometheus
    /// `fc_mediation_duration_seconds` exporter. `counts[i]` is the number
    /// of observations `<= bounds[i]` (cumulative "le" semantics); `+Inf`
    /// is implicit and equals `count`.
    public record HistogramSnapshot(double[] bounds, long[] counts, double sumSeconds, long count) {

        public HistogramSnapshot {
            bounds = bounds.clone();
            counts = counts.clone();
        }

        @Override
        public double[] bounds() {
            return bounds.clone();
        }

        @Override
        public long[] counts() {
            return counts.clone();
        }
    }

    /// Prometheus histogram upper bounds in seconds (constant 44) — the
    /// default `client_golang` latency buckets. `+Inf` is implicit.
    private static final double[] MEDIATION_BUCKETS_SECONDS =
            {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};

    private record Sample(Instant at, long durationMs, boolean success) {
    }

    private final Config config;
    private final Clock clock;

    private final AtomicLong totalSuccess = new AtomicLong();
    private final AtomicLong totalFailure = new AtomicLong();
    private final AtomicLong totalRateLimited = new AtomicLong();
    private final AtomicLong totalSuppressed = new AtomicLong();

    /// `fc_router_mediation_http_version_total{version=...}`
    /// (`docs/spec/router-h2.md` §3) — kept as two counters rather than a
    /// map since [HttpClient.Version] only ever negotiates one of these two
    /// over HTTP (never HTTP/3 for outbound mediation).
    private final AtomicLong totalHttpVersion2 = new AtomicLong();
    private final AtomicLong totalHttpVersion1_1 = new AtomicLong();

    /// Cumulative histogram counters — reporting-only, never read for a
    /// decision, so plain atomics rather than the lock below.
    private final AtomicLong durationCount = new AtomicLong();
    private final AtomicLong durationSumMs = new AtomicLong();
    private final AtomicLong[] durationBuckets;

    /// Guards the three rolling-window buffers below: a `record*` call
    /// trims-then-appends one of them, and `snapshot()` needs a consistent
    /// copy of all three, so they move together under one lock.
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Deque<Instant> rateLimitedEvents = new ArrayDeque<>();
    private final Deque<Instant> suppressedEvents = new ArrayDeque<>();

    public PoolMetricsCollector(Clock clock) {
        this(Config.DEFAULTS, clock);
    }

    public PoolMetricsCollector(Config config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.durationBuckets = new AtomicLong[MEDIATION_BUCKETS_SECONDS.length];
        Arrays.setAll(durationBuckets, i -> new AtomicLong());
    }

    @Override
    public void recordSuccess(Duration took) {
        totalSuccess.incrementAndGet();
        addSample(took, true);
    }

    @Override
    public void recordFailure(Duration took) {
        totalFailure.incrementAndGet();
        addSample(took, false);
    }

    @Override
    public void recordTransient(Duration took) {
        // Not a totalFailure — the message will be retried — but it still
        // counts as a non-success sample so windowed success rate reflects
        // current state (matches Go's RecordTransient).
        addSample(took, false);
    }

    @Override
    public void recordRateLimited() {
        totalRateLimited.incrementAndGet();
        recordEvent(rateLimitedEvents);
    }

    @Override
    public void recordSuppressed() {
        totalSuppressed.incrementAndGet();
        recordEvent(suppressedEvents);
    }

    @Override
    public void recordHttpVersion(HttpClient.Version version) {
        if (version == HttpClient.Version.HTTP_2) {
            totalHttpVersion2.incrementAndGet();
        } else {
            totalHttpVersion1_1.incrementAndGet();
        }
    }

    /// The cumulative count for one label of
    /// `fc_router_mediation_http_version_total` — a plain getter (like the
    /// other totals, exposed through [#snapshot] for the dashboard-shaped
    /// view) rather than folded into [Snapshot], since that record is
    /// specifically the per-pool delivery-outcome shape and this counter is
    /// router-wide, not per-pool (`docs/spec/router-h2.md` §3).
    public long httpVersionCount(HttpClient.Version version) {
        return version == HttpClient.Version.HTTP_2 ? totalHttpVersion2.get() : totalHttpVersion1_1.get();
    }

    private void recordEvent(Deque<Instant> events) {
        var now = clock.instant();
        var cutoff = now.minus(config.longWindow());
        lock.lock();
        try {
            while (!events.isEmpty() && events.peekFirst().isBefore(cutoff)) {
                events.pollFirst();
            }
            events.addLast(now);
        } finally {
            lock.unlock();
        }
    }

    private void addSample(Duration took, boolean success) {
        long ms = took.toMillis();
        observeDuration(ms);

        var now = clock.instant();
        var cutoff = now.minus(config.longWindow());
        lock.lock();
        try {
            // Drop samples older than the long window first — they can
            // never count toward last30Min either.
            while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
                samples.pollFirst();
            }
            samples.addLast(new Sample(now, ms, success));
            while (samples.size() > config.maxSamples()) {
                samples.pollFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    private void observeDuration(long durationMs) {
        durationCount.incrementAndGet();
        durationSumMs.addAndGet(durationMs);
        double secs = durationMs / 1000.0;
        for (int i = 0; i < MEDIATION_BUCKETS_SECONDS.length; i++) {
            if (secs <= MEDIATION_BUCKETS_SECONDS[i]) {
                durationBuckets[i].incrementAndGet();
            }
        }
    }

    public HistogramSnapshot histogramSnapshot() {
        long[] counts = new long[durationBuckets.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = durationBuckets[i].get();
        }
        return new HistogramSnapshot(MEDIATION_BUCKETS_SECONDS, counts,
                durationSumMs.get() / 1000.0, durationCount.get());
    }

    /// Takes a snapshot for the monitoring API. Safe to call at any time;
    /// the rolling buffers are copied under the lock so percentile sorting
    /// never disturbs the live buffer.
    public Snapshot snapshot() {
        long success = totalSuccess.get();
        long failure = totalFailure.get();
        long rateLimited = totalRateLimited.get();
        long suppressed = totalSuppressed.get();

        List<Sample> samplesCopy;
        List<Instant> rateLimitedCopy;
        List<Instant> suppressedCopy;
        lock.lock();
        try {
            samplesCopy = new ArrayList<>(samples);
            rateLimitedCopy = new ArrayList<>(rateLimitedEvents);
            suppressedCopy = new ArrayList<>(suppressedEvents);
        } finally {
            lock.unlock();
        }

        var now = clock.instant();
        var shortCutoff = now.minus(config.shortWindow());
        var longCutoff = now.minus(config.longWindow());

        double successRate = totalRate(success, failure);

        var last5 = windowed(sinceCutoff(samplesCopy, shortCutoff), config.shortWindow(),
                countSince(rateLimitedCopy, shortCutoff), countSince(suppressedCopy, shortCutoff), now);
        var last30 = windowed(sinceCutoff(samplesCopy, longCutoff), config.longWindow(),
                countSince(rateLimitedCopy, longCutoff), countSince(suppressedCopy, longCutoff), now);

        return new Snapshot(success, failure, rateLimited, suppressed, successRate,
                processingTime(samplesCopy), last5, last30);
    }

    private static double totalRate(long success, long failure) {
        long total = success + failure;
        return total > 0 ? (double) success / total : 1.0;
    }

    /// The suffix of a chronologically-ordered (oldest-first) list with
    /// `at >= cutoff` — the same "linear scan from the front" the Go does,
    /// relying on insertion order being monotonic.
    private static List<Sample> sinceCutoff(List<Sample> ordered, Instant cutoff) {
        int i = 0;
        while (i < ordered.size() && ordered.get(i).at().isBefore(cutoff)) {
            i++;
        }
        return ordered.subList(i, ordered.size());
    }

    private static long countSince(List<Instant> orderedEvents, Instant cutoff) {
        int i = 0;
        while (i < orderedEvents.size() && orderedEvents.get(i).isBefore(cutoff)) {
            i++;
        }
        return orderedEvents.size() - i;
    }

    private static WindowedMetrics windowed(List<Sample> inWindow, Duration window,
                                             long rateLimitedCount, long suppressedCount, Instant now) {
        long success = 0;
        long failure = 0;
        for (var s : inWindow) {
            if (s.success()) {
                success++;
            } else {
                failure++;
            }
        }
        long total = success + failure;
        double successRate = total > 0 ? (double) success / total : 1.0;
        double secs = window.toMillis() / 1000.0;
        double throughput = secs > 0 ? total / secs : 0.0;
        return new WindowedMetrics(success, failure, rateLimitedCount, suppressedCount, successRate,
                throughput, processingTime(inWindow), now.minus(window), window.toSeconds());
    }

    private static ProcessingTimeMetrics processingTime(List<Sample> samples) {
        if (samples.isEmpty()) {
            return ProcessingTimeMetrics.EMPTY;
        }
        long[] durations = new long[samples.size()];
        long sum = 0;
        for (int i = 0; i < samples.size(); i++) {
            long ms = samples.get(i).durationMs();
            durations[i] = ms;
            sum += ms;
        }
        Arrays.sort(durations);
        double avg = (double) sum / durations.length;
        return new ProcessingTimeMetrics(avg, durations[0], durations[durations.length - 1],
                quantile(durations, 0.50), quantile(durations, 0.95), quantile(durations, 0.99),
                durations.length);
    }

    /// Nearest-rank percentile over a sorted (ascending) array — close to
    /// HdrHistogram's `value_at_quantile`, matching the Go.
    private static long quantile(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        if (q <= 0) {
            return sorted[0];
        }
        if (q >= 1) {
            return sorted[sorted.length - 1];
        }
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        } else if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx];
    }
}

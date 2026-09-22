package io.flowcatalyst.router.lifecycle;

import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.queue.QueueMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/// Keeps broker-side queue depths without asking the broker per request
/// (`docs/spec/router.md` §9.3).
///
/// ### Why a cache at all
///
/// `pending` and `inFlight` cost a round-trip to the broker — a
/// `GetQueueAttributes` on SQS, a query on Postgres. The monitoring API is
/// polled every few seconds by a dashboard, so reading them per request
/// would turn one open browser tab into steady broker load, and several tabs
/// into a self-inflicted rate limit. They are sampled on a schedule instead
/// and served from here.
///
/// ### Why a history rather than just the latest
///
/// A raw counter answers "how many since the process started", which nobody
/// asks. The useful question is "how many in the last five minutes", and
/// that needs a baseline to subtract. Keeping a rolling window of snapshots
/// is what makes a windowed answer possible at all.
public final class BrokerStatsCache {

    private static final Logger log = LoggerFactory.getLogger(BrokerStatsCache.class);

    /// How long snapshots are kept. Bounds the window a caller may ask for,
    /// and bounds the memory this holds.
    public static final Duration HISTORY = Duration.ofMinutes(30);

    /// Age reported before the first refresh has happened. Negative rather
    /// than zero deliberately: zero would read as "just refreshed", which is
    /// the opposite of the truth.
    public static final long NEVER_REFRESHED = -1;

    private record Snapshot(Instant at, Map<String, QueueMetrics> byQueue) {
    }

    /// The most recent reading per queue, overwritten on each refresh.
    private final Map<String, QueueMetrics> latest = new LinkedHashMap<>();

    /// Rolling snapshots, oldest first, trimmed to [#HISTORY].
    private final Deque<Snapshot> history = new ArrayDeque<>();

    /// Queues currently unreadable, so the QUEUE_HEALTH warning fires once on
    /// entry into a failing streak and clears with an INFO on recovery
    /// (§7.3), the same shape as [io.flowcatalyst.router.manager.ConsumerLoop]'s
    /// CONNECTION warning.
    private final Set<String> unreachable = ConcurrentHashMap.newKeySet();

    private final Clock clock;
    private final Warnings warnings;
    private volatile Instant lastRefresh;

    public BrokerStatsCache(Clock clock) {
        this(clock, Warnings.NO_OP);
    }

    public BrokerStatsCache(Clock clock, Warnings warnings) {
        this.clock = clock;
        this.warnings = warnings;
    }

    /// Samples every queue and records the result.
    ///
    /// A queue whose metrics are unavailable is **skipped, not zeroed**: its
    /// previous reading stays, because "we could not ask" is not the same as
    /// "there is nothing there", and showing zero depth for a queue we simply
    /// failed to reach would be actively misleading during an outage.
    ///
    /// ### Why the sampling is not done under the lock
    ///
    /// Each supplier may cost a broker round-trip, and there are now two
    /// callers: the housekeeping tick and an operator hitting
    /// `POST /monitoring/broker-stats/refresh`. Holding the monitor across the
    /// I/O would make one unreachable broker block **every read of this
    /// cache** for as long as its client takes to time out — so the endpoint
    /// built to diagnose an outage would be the thing that hangs the dashboard
    /// during one. The whole point of a cache is that reads never wait on a
    /// broker.
    public void refresh(Map<String, Supplier<java.util.Optional<QueueMetrics>>> queues) {
        var sampled = new HashMap<String, QueueMetrics>();
        queues.forEach((queueId, source) -> {
            try {
                var result = source.get();
                if (result.isPresent()) {
                    sampled.put(queueId, result.get());
                    recordReachable(queueId);
                } else {
                    recordUnreachable(queueId);
                }
            } catch (RuntimeException e) {
                log.atWarn().setMessage("could not read broker metrics")
                        .addKeyValue("queue", queueId)
                        .setCause(e)
                        .log();
                recordUnreachable(queueId);
            }
        });
        // Stamped after the sampling, not before: the age answers "how stale
        // is the newest reading", and a slow sweep that started a minute ago
        // did not produce minute-old numbers.
        store(clock.instant(), sampled);
    }

    /// Raises the once-per-streak QUEUE_HEALTH warning the first time
    /// `queueId`'s metrics go unreadable (§7.3) — an empty supplier answer or
    /// a throwing one are both "the broker could not be asked", so both feed
    /// the same streak.
    private void recordUnreachable(String queueId) {
        if (unreachable.add(queueId)) {
            warnings.raise(Warnings.Severity.WARNING, "QUEUE_HEALTH",
                    "queue " + queueId + " metrics are unreachable");
        }
    }

    /// Clears a failing streak with an INFO recovery notice, once.
    private void recordReachable(String queueId) {
        if (unreachable.remove(queueId)) {
            warnings.raise(Warnings.Severity.INFO, "QUEUE_HEALTH", "queue " + queueId + " metrics recovered");
        }
    }

    /// Records one completed sample. Short and lock-held; no I/O.
    ///
    /// A sample that finished *behind* one already recorded is dropped rather
    /// than applied. Two refreshes can now overlap — a tick and an operator's
    /// forced refresh — and letting the slower one land last would rewind
    /// `latest` to older numbers and then report them as fresh.
    private synchronized void store(Instant at, Map<String, QueueMetrics> sampled) {
        if (lastRefresh != null && at.isBefore(lastRefresh)) {
            return;
        }
        latest.putAll(sampled);
        history.addLast(new Snapshot(at, Map.copyOf(sampled)));
        trim(at);
        lastRefresh = at;
    }

    private void trim(Instant now) {
        var cutoff = now.minus(HISTORY);
        // Keep one snapshot at or before the cutoff: it is the baseline for a
        // window exactly as long as the history, and dropping it would make
        // the widest supported window unanswerable.
        while (history.size() > 1 && history.peekFirst().at().isBefore(cutoff)
                && history.stream().skip(1).findFirst().map(s -> !s.at().isAfter(cutoff)).orElse(false)) {
            history.removeFirst();
        }
    }

    /// Seconds since the last refresh, or [#NEVER_REFRESHED].
    public synchronized long ageSeconds() {
        return lastRefresh == null ? NEVER_REFRESHED : Duration.between(lastRefresh, clock.instant()).toSeconds();
    }

    /// The most recent reading for every queue.
    public synchronized Map<String, QueueMetrics> latest() {
        return Map.copyOf(latest);
    }

    /// Counters over `window`, as deltas against the newest baseline at or
    /// before `now - window`.
    ///
    /// A window of zero (or none) returns the lifetime counters unchanged.
    ///
    /// **Deltas saturate at zero.** A counter that appears to have gone
    /// backwards means the consumer was rebuilt and its process-local
    /// counters restarted, not that messages were un-acked. Reporting a
    /// negative rate would be nonsense on a dashboard; reporting zero says
    /// "nothing measurable since the baseline", which is true.
    ///
    /// **A queue with no baseline reads as zero**, not as its lifetime total:
    /// a queue that appeared *after* the baseline has no measurable activity
    /// *within the window*, and showing its whole history would make a new
    /// queue look like a spike.
    public synchronized Map<String, QueueMetrics> windowed(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            return Map.copyOf(latest);
        }
        var baseline = baselineAt(clock.instant().minus(window));
        var out = new LinkedHashMap<String, QueueMetrics>();
        latest.forEach((queueId, live) -> {
            var since = baseline.get(queueId);
            if (since == null) {
                out.put(queueId, new QueueMetrics(live.pending(), live.inFlight(), 0, 0, 0, 0));
                return;
            }
            // Depths are levels, not counters — they are reported as they are
            // rather than differenced, because "pending changed by 4" is not
            // what a queue-depth column means.
            out.put(queueId, new QueueMetrics(
                    live.pending(), live.inFlight(),
                    delta(live.polled(), since.polled()),
                    delta(live.acked(), since.acked()),
                    delta(live.nacked(), since.nacked()),
                    delta(live.deferred(), since.deferred())));
        });
        return Map.copyOf(out);
    }

    private static long delta(long live, long baseline) {
        return Math.max(0, live - baseline);
    }

    /// The newest snapshot at or before `at`, falling back to the oldest we
    /// hold when the history does not reach back that far — which is the
    /// honest answer for a window wider than our history.
    private Map<String, QueueMetrics> baselineAt(Instant at) {
        Map<String, QueueMetrics> chosen = null;
        for (var snapshot : history) {
            if (!snapshot.at().isAfter(at)) {
                chosen = snapshot.byQueue();
            } else if (chosen == null) {
                chosen = snapshot.byQueue();
                break;
            } else {
                break;
            }
        }
        return chosen == null ? Map.of() : chosen;
    }

    int historySize() {
        return history.size();
    }
}

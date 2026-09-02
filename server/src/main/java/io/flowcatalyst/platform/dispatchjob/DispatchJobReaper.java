package io.flowcatalyst.platform.dispatchjob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// The dispatch-job reaper (dispatch-seam spec §7) — the **backstop** half of
/// the A-01 `BLOCK_ON_ERROR` group recovery: catches what
/// [io.flowcatalyst.platform.dispatchjob.settled.SettledApi] misses (a
/// dropped `/api/dispatch/settled` call, or a router that crashes between
/// ACKing the siblings and reporting them) by periodically sweeping
/// [DispatchJobRepository#sweepStrandedSiblings].
///
/// **Not leader-gated** (spec §7, §12): every sweep is one idempotent,
/// status-guarded `UPDATE`, safe under concurrent execution from multiple
/// platform instances — unlike the scheduler's claim/dispatch loops, whose
/// per-group FIFO ordering requires a single active leader, nothing here
/// makes an ordering-sensitive decision.
///
/// Cadence and liveness cutoff are the Go defaults (spec §3's timing table):
/// [#DEFAULT_INTERVAL] 2 minutes (double the purger's 1-minute cadence,
/// since this sweep self-joins across partitions), [#DEFAULT_PROCESSING_LIVE_AFTER]
/// 45 minutes (sized above the router's documented 15-min-per-attempt ×
/// up-to-3-attempts callback contract so the reaper never races a delivery
/// legitimately still in flight).
public final class DispatchJobReaper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchJobReaper.class);

    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(2);
    public static final Duration DEFAULT_PROCESSING_LIVE_AFTER = Duration.ofMinutes(45);

    /// Recorded in `last_error` on every row this reaper resets — carries the
    /// literal substring `"reaper"` so an operator can distinguish a reaper
    /// reset from a settled-hook reset or a human resend (spec §7).
    static final String REASON = "reaper: swept as a stranded BLOCK_ON_ERROR sibling behind a failed head";

    private final DispatchJobRepository repo;
    private final Duration interval;
    private final Duration processingLiveAfter;
    private final ScheduledExecutorService executor;

    public DispatchJobReaper(DispatchJobRepository repo) {
        this(repo, DEFAULT_INTERVAL, DEFAULT_PROCESSING_LIVE_AFTER);
    }

    public DispatchJobReaper(DispatchJobRepository repo, Duration interval, Duration processingLiveAfter) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.processingLiveAfter = Objects.requireNonNull(processingLiveAfter, "processingLiveAfter");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dispatchjob-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    /// Starts the periodic sweep (first run immediate, then every
    /// [#interval]). A failed sweep is logged and retried on the next tick —
    /// it never kills the loop.
    public DispatchJobReaper start() {
        executor.scheduleWithFixedDelay(this::sweepSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    /// One sweep, exposed directly for tests and for a manual trigger: the
    /// ids reset to `PENDING`.
    public List<String> sweepOnce() {
        return repo.sweepStrandedSiblings(Instant.now().minus(processingLiveAfter), REASON);
    }

    private void sweepSafely() {
        try {
            List<String> reset = sweepOnce();
            if (!reset.isEmpty()) {
                LOG.info("dispatch job reaper: reset {} stranded sibling(s)", reset.size());
            }
        } catch (RuntimeException e) {
            LOG.error("dispatch job reaper sweep failed; will retry next tick", e);
        }
    }

    /// Stops the periodic sweep. Not currently invoked from
    /// `Server.Running#stop()` (that shutdown path has no hook for this
    /// unit's background subsystems yet — see `Server.java`'s own
    /// `TODO(port)` for the purger/scheduler/stream/outbox loops); the
    /// executor's threads are daemon threads, so an un-stopped reaper does
    /// not keep the JVM alive.
    @Override
    public void close() {
        executor.shutdownNow();
    }
}

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
/// **15 minutes** (owner ruling 2026-09-22, the old system's value — was 45,
/// sized above the mediator's 15-min-per-attempt × 3 attempts; the owner
/// accepts that a delivery still hanging on its second attempt is redriven —
/// a duplicate to a target that is already broken). With the stale-`QUEUED`
/// sweep gone, this is the platform's only automatic redrive.
public final class DispatchJobReaper implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchJobReaper.class);

    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(2);
    public static final Duration DEFAULT_PROCESSING_LIVE_AFTER = Duration.ofMinutes(15);

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

    /// Starts the periodic sweep. The first run waits one full [#interval] —
    /// the sweep is a backstop for a rare failure, not a hot path (spec §3),
    /// so nothing depends on sweeping the instant the platform boots, and a
    /// zero initial delay only meant every test that builds a `Platform` paid
    /// for an immediate, useless sweep of the shared test database. A failed
    /// sweep is logged and retried on the next tick — it never kills the loop.
    public DispatchJobReaper start() {
        executor.scheduleWithFixedDelay(this::sweepSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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
                LOG.atInfo().setMessage("dispatch job reaper: reset stranded sibling(s)")
                        .addKeyValue("count", reset.size())
                        .log();
            }
        } catch (RuntimeException e) {
            LOG.error("dispatch job reaper sweep failed; will retry next tick", e);
        }
    }

    /// Stops the periodic sweep. Invoked from `Server.Running#stop()`, which
    /// holds the instance `Platform#register` returns for exactly this
    /// purpose — a leaked, un-stopped reaper otherwise sweeps the shared test
    /// database on a background thread for the life of the JVM.
    @Override
    public void close() {
        executor.shutdownNow();
    }

    /// Whether [#close()] has run — the executor accepts no further tasks.
    /// Test-only: a pinning assertion needs an observable "this loop is
    /// actually gone," not the absence of a symptom.
    public boolean isClosed() {
        return executor.isShutdown();
    }
}

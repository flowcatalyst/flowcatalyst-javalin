package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.scheduler.jfr.QueueSweepEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// The leader's housekeeping over the dispatch queue (dispatch-queue spec §3, §5,
/// §6): every sweep runs on one thread, only while this instance is the leader,
/// on the scheduler's own pool (the lifecycle it is given is built over it).
///
///  - **stale `QUEUED`** (every 60 s): a job still `QUEUED` 15 minutes after its
///    last update (owner ruling 2026-10-04) goes back to `PENDING`; a duplicate
///    that causes is dropped by the router or skipped by the delivery callback.
///    There is no `PROCESSING` sweep here: the reaper owns that.
///  - **reconcile** (every 60 s): [DispatchJobLifecycle#reconcileQueue], bounded
///    to 5,000 rows per statement. Pass (a) restores the queue row of a `PENDING`
///    job that has none (a claimer that died, or a failed restore) once it is 60 s
///    old, EXCLUDING this process's in-flight ids (those are being published). Every
///    non-zero count is a WARN: a crash's leftovers, a bug, or an older binary
///    writing the table. (The leader's start-up pass is [PendingJobPoller]'s.)
///  - **backlog gauge** (every 15 s): the depth and the oldest wait of the
///    unclaimed due rows.
///
/// An exception in one sweep is logged and counted; it never stops the others
/// or the schedule.
final class QueueMaintenance implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(QueueMaintenance.class);

    /// The cadences and thresholds. Tests shorten them.
    record Timing(Duration sweepInterval, Duration backlogInterval, Duration staleQueuedAfter,
                  Duration reconcileJobAge, int reconcileMaxRows) {
        static final Timing DEFAULTS = new Timing(Duration.ofSeconds(60), Duration.ofSeconds(15),
                Duration.ofMinutes(15), Duration.ofSeconds(60), 5_000);
    }

    private final DispatchJobLifecycle lifecycle;
    private final Supplier<List<String>> inFlightIds;
    private final BooleanSupplier leader;
    private final SchedulerMetrics metrics;
    private final Timing timing;
    /// The poller's claim lock: held around "read the in-flight ids + insert missing queue rows" so a job being
    /// claimed right now (PENDING, no row, not yet in flight) is not mistaken for a crashed claimer's leftover.
    private final java.util.concurrent.locks.Lock claimLock;
    private final ScheduledExecutorService executor;

    QueueMaintenance(DispatchJobLifecycle lifecycle, Supplier<List<String>> inFlightIds, BooleanSupplier leader,
                     SchedulerMetrics metrics, Timing timing) {
        this(lifecycle, inFlightIds, leader, metrics, timing, new java.util.concurrent.locks.ReentrantLock());
    }

    QueueMaintenance(DispatchJobLifecycle lifecycle, Supplier<List<String>> inFlightIds, BooleanSupplier leader,
                     SchedulerMetrics metrics, Timing timing, java.util.concurrent.locks.Lock claimLock) {
        this.claimLock = Objects.requireNonNull(claimLock, "claimLock");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.inFlightIds = Objects.requireNonNull(inFlightIds, "inFlightIds");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.timing = Objects.requireNonNull(timing, "timing");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dispatch-queue-maintenance");
            t.setDaemon(true);
            return t;
        });
    }

    /// Starts the schedule; the first run of each waits one interval.
    QueueMaintenance start() {
        executor.scheduleWithFixedDelay(this::sweepAll, timing.sweepInterval().toMillis(),
                timing.sweepInterval().toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::sampleBacklog, timing.backlogInterval().toMillis(),
                timing.backlogInterval().toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    /// The three sweeps, if this instance is the leader. Exposed for tests.
    void sweepAll() {
        if (!leader.getAsBoolean()) return;
        guarded("stale QUEUED", this::recoverStaleQueued);
        guarded("reconcile", this::reconcile);
    }

    /// Returns the jobs still `QUEUED` after the threshold to `PENDING`.
    int recoverStaleQueued() {
        var event = new QueueSweepEvent();
        event.begin();
        int recovered = lifecycle.recoverStaleQueued(Instant.now().minus(timing.staleQueuedAfter()));
        metrics.staleQueuedRecovered.add(recovered);
        commit(event, "stale_queued", recovered);
        if (recovered > 0) {
            LOG.atWarn().setMessage("returned stale QUEUED jobs to PENDING; they are dispatched again")
                    .addKeyValue("count", recovered)
                    .addKeyValue("olderThan", timing.staleQueuedAfter())
                    .log();
        }
        return recovered;
    }

    /// One reconcile pass.
    DispatchJobLifecycle.Reconciled reconcile() {
        var event = new QueueSweepEvent();
        event.begin();
        int inserted;
        claimLock.lock();
        try {
            inserted = lifecycle.restoreMissing(timing.reconcileMaxRows(), timing.reconcileJobAge(), inFlightIds.get());
        } finally {
            claimLock.unlock();
        }
        var rest = lifecycle.reconcileRows(timing.reconcileMaxRows());
        var r = new DispatchJobLifecycle.Reconciled(inserted, rest.deleted(), rest.refreshed());
        metrics.reconcileInserted.add(r.inserted());
        metrics.reconcileDeleted.add(r.deleted());
        metrics.reconcileRefreshed.add(r.refreshed());
        commit(event, "reconcile", r.inserted() + r.deleted() + r.refreshed());
        if (!r.isClean()) {
            LOG.atWarn().setMessage("the dispatch queue disagreed with the jobs table and was repaired; this means "
                            + "a bug, or an older binary writing msg_dispatch_queue")
                    .addKeyValue("restoredMissing", r.inserted())
                    .addKeyValue("deletedOrphaned", r.deleted())
                    .addKeyValue("refreshedStale", r.refreshed())
                    .log();
        }
        return r;
    }

    /// Samples the backlog gauge (leader only; a non-leader reports zero).
    void sampleBacklog() {
        if (!leader.getAsBoolean()) {
            metrics.backlog(0, 0);
            return;
        }
        guarded("backlog", () -> {
            var backlog = lifecycle.queueBacklog();
            double age = backlog.oldestEnqueuedAt() == null ? 0
                    : Math.max(0, Duration.between(backlog.oldestEnqueuedAt(), Instant.now()).toMillis() / 1000.0);
            metrics.backlog(backlog.depth(), age);
        });
    }

    private void guarded(String what, Runnable sweep) {
        try {
            sweep.run();
        } catch (RuntimeException e) {
            metrics.maintenanceErrors.increment();
            LOG.atWarn().setMessage("dispatch queue maintenance sweep failed; will retry on the next tick")
                    .addKeyValue("sweep", what)
                    .setCause(e)
                    .log();
        }
    }

    private static void commit(QueueSweepEvent event, String sweep, int changed) {
        if (event.shouldCommit()) {
            event.sweep = sweep;
            event.changed = changed;
            event.commit();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.scheduler.jfr.QueueSweepEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/// The leader's housekeeping around the dispatch scheduler: every sweep runs on one thread, only while this
/// instance is the leader, on the scheduler's own pool (the lifecycle and repository it is given are built over it).
///
///  - **stale `QUEUED`** (every 60 s): a job still `QUEUED` 15 minutes after its last update (owner ruling
///    2026-10-04) goes back to `PENDING`; a duplicate that causes is dropped by the router or skipped by the
///    delivery callback. There is no `PROCESSING` sweep here: the reaper owns that.
///  - **backlog gauge** (every 30 s): the number of `PENDING` jobs, saturating at 100,001 (an index range of at
///    most that many entries, never a scan of a huge backlog), and the age of the first due job in claim order.
///
/// An exception in one sweep is logged and counted; it never stops the others or the schedule.
final class QueueMaintenance implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(QueueMaintenance.class);

    /// The cadences and thresholds. Tests shorten them.
    record Timing(Duration sweepInterval, Duration backlogInterval, Duration staleQueuedAfter) {
        static final Timing DEFAULTS = new Timing(Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(15));
    }

    private final DispatchJobLifecycle lifecycle;
    private final DispatchJobRepository repository;
    private final BooleanSupplier leader;
    private final SchedulerMetrics metrics;
    private final Timing timing;
    private final ScheduledExecutorService executor;

    QueueMaintenance(DispatchJobLifecycle lifecycle, DispatchJobRepository repository, BooleanSupplier leader,
                     SchedulerMetrics metrics, Timing timing) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.repository = Objects.requireNonNull(repository, "repository");
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

    /// The sweeps, if this instance is the leader. Exposed for tests.
    void sweepAll() {
        if (!leader.getAsBoolean()) return;
        guarded("stale QUEUED", this::recoverStaleQueued);
    }

    /// Returns the jobs still `QUEUED` after the threshold to `PENDING`.
    int recoverStaleQueued() {
        var event = new QueueSweepEvent();
        event.begin();
        int recovered = lifecycle.recoverStaleQueued(Instant.now().minus(timing.staleQueuedAfter()));
        metrics.staleQueuedRecovered.add(recovered);
        if (event.shouldCommit()) {
            event.sweep = "stale_queued";
            event.changed = recovered;
            event.commit();
        }
        if (recovered > 0) {
            LOG.atWarn().setMessage("returned stale QUEUED jobs to PENDING; they are dispatched again")
                    .addKeyValue("count", recovered)
                    .addKeyValue("olderThan", timing.staleQueuedAfter())
                    .log();
        }
        return recovered;
    }

    /// Samples the backlog gauge (leader only; a non-leader reports zero).
    void sampleBacklog() {
        if (!leader.getAsBoolean()) {
            metrics.backlog(0, 0);
            return;
        }
        guarded("backlog", () -> {
            var backlog = repository.pendingBacklog();
            double age = backlog.oldestCreatedAt() == null ? 0
                    : Math.max(0, Duration.between(backlog.oldestCreatedAt(), Instant.now()).toMillis() / 1000.0);
            metrics.backlog(backlog.count(), age);
        });
    }

    private void guarded(String what, Runnable sweep) {
        try {
            sweep.run();
        } catch (RuntimeException e) {
            metrics.maintenanceErrors.increment();
            LOG.atWarn().setMessage("dispatch maintenance sweep failed; will retry on the next tick")
                    .addKeyValue("sweep", what)
                    .setCause(e)
                    .log();
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

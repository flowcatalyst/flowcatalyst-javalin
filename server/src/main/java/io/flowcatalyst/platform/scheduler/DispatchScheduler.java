package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.prometheus.metrics.model.registry.MultiCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/// The dispatch-job scheduler (dispatch-seam spec §3, §11, §12): one daemon
/// thread runs [PendingJobPoller]'s claim loop; the poller hands claimed rows
/// to N dispatcher lanes ([DispatchLanes]) that publish and mark `QUEUED`. The
/// poller never waits for a publish — it blocks only when the buffer
/// (`FC_SCHEDULER_BUFFER_CAPACITY` claimed-and-unsettled jobs) is full — and
/// sleeps the poll interval only when it has nothing to do (see
/// [PendingJobPoller.PollResult#backOff]). A second thread runs the leader's
/// queue housekeeping ([QueueMaintenance]). [#close] stops the loop and the
/// lanes (CONVENTIONS §5: an explicit stop signal, no fire-and-forget thread).
///
/// Sizes are [SchedulerConfig]: the defaults are the spec's, and three are
/// operator-overridable through `Env` (`FC_SCHEDULER_BUFFER_CAPACITY`,
/// `FC_SCHEDULER_DISPATCHERS`, `FC_SCHEDULER_BATCH_SIZE`).
public final class DispatchScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchScheduler.class);

    /// Poll cadence (spec §3 timing table `Config.PollInterval`).
    static final Duration POLL_INTERVAL = SchedulerConfig.DEFAULT_POLL_INTERVAL;

    private final PendingJobPoller poller;
    private final SchedulerConfig config;
    private final QueueMaintenance maintenance;
    private final Thread thread;
    private volatile boolean closed;

    /// The leader's housekeeping over the queue ([QueueMaintenance]), including
    /// the stale-`QUEUED` recovery loop.
    ///
    /// Stale-`QUEUED` recovery (owner ruling 2026-10-04, reversing 2026-09-22):
    /// the sweep is restored, at 15 minutes. A message the broker lost or expired
    /// after the job was marked `QUEUED` would otherwise leave the job `QUEUED`
    /// for ever (the reaper only redrives `PROCESSING` rows). The duplicate the
    /// sweep can cause for a message the router is merely holding (a deferral for
    /// a full pool) is cheap: the router ACK-drops a copy whose original is in its
    /// pipeline, and the delivery callback skips a job that has moved on. The
    /// 5-minute revert the 2026-09-22 ruling removed re-published a deferred
    /// message every 5 minutes for up to an hour; 15 minutes is the owner's
    /// accepted trade. There is no `PROCESSING` sweep here: that is the reaper's
    /// ([io.flowcatalyst.platform.dispatchjob.DispatchJobReaper], 15 min).
    DispatchScheduler(PendingJobPoller poller, SchedulerConfig config, QueueMaintenance maintenance) {
        this.poller = Objects.requireNonNull(poller, "poller");
        this.config = Objects.requireNonNull(config, "config");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.thread = new Thread(this::loop, "dispatch-scheduler-" + THREAD_COUNT.getAndIncrement());
        this.thread.setDaemon(true);
    }

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    /// Wires and starts the scheduler. Fails closed without a usable
    /// `FLOWCATALYST_APP_KEY` (dispatch-seam spec §11) — logged as an ERROR,
    /// returns `null` rather than starting a scheduler that would sign every
    /// dispatch callback with no real secret, matching Go's `StartScheduler`
    /// (`internal/server/subsystems.go:63-83`).
    ///
    /// `publisher` and `leader` are supplied by the caller (`Server`):
    /// picking the broker (`FC_DEFAULT_BROKER`) and building the standby
    /// leader election are composition-root decisions, not this class's.
    /// `appKey` and `processingEndpoint` are the two `Env` values this unit
    /// needs — read once at the composition root and passed as values, per
    /// CONVENTIONS §8 "subsystem knobs reach the composition root through
    /// `Env`" (a value-taking factory here, not an `Env`-taking one).
    public static DispatchScheduler start(String appKey, String processingEndpoint, DataSource pool,
                                           DispatchPublisher publisher, BooleanSupplier leader,
                                           SchedulerConfig config) {
        HmacTokenVerifier authVerifier;
        try {
            authVerifier = HmacTokenVerifier.fromAppKey(appKey);
        } catch (IllegalArgumentException e) {
            LOG.error("scheduler disabled: cannot derive dispatch-auth secret; set FLOWCATALYST_APP_KEY", e);
            return null;
        }
        var repository = new DispatchJobRepository(pool);
        var lifecycle = new DispatchJobLifecycle(pool);
        var pausedCache = new PausedConnectionCache(pool);
        var poolCodes = new PoolCodeResolver(pool);
        var poller = new PendingJobPoller(pool, repository, lifecycle, pausedCache, poolCodes, publisher, authVerifier,
                processingEndpoint, leader, config);
        var maintenance = new QueueMaintenance(lifecycle, () -> poller.lanes().inFlightIds(), leader, poller.metrics(),
                QueueMaintenance.Timing.DEFAULTS, poller.claimLock());
        var scheduler = new DispatchScheduler(poller, config, maintenance);
        scheduler.thread.start();
        maintenance.start();
        LOG.atInfo().setMessage("dispatch scheduler started")
                .addKeyValue("interval", config.pollInterval())
                .addKeyValue("bufferCapacity", config.bufferCapacity())
                .addKeyValue("dispatchers", config.dispatchers())
                .addKeyValue("batchSize", config.batchSize())
                .log();
        return scheduler;
    }

    /// The defaults.
    public static DispatchScheduler start(String appKey, String processingEndpoint, DataSource pool,
                                           DispatchPublisher publisher, BooleanSupplier leader) {
        return start(appKey, processingEndpoint, pool, publisher, leader, SchedulerConfig.DEFAULTS);
    }

    /// Test-only: the defaults with a smaller claim batch, for the same reason
    /// the poller's tests use one (CONVENTIONS §6, no truncation between tests).
    static DispatchScheduler start(String appKey, String processingEndpoint, DataSource pool,
                                    DispatchPublisher publisher, BooleanSupplier leader, int batchSize) {
        return start(appKey, processingEndpoint, pool, publisher, leader,
                SchedulerConfig.DEFAULTS.withBatchSize(batchSize));
    }

    /// The claim loop: poll; when the poll says there is more waiting, poll
    /// again at once; otherwise wait the poll interval. A claim that fails is
    /// logged and waited out the same way.
    private void loop() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            boolean backOff;
            try {
                backOff = poller.pollOnce().backOff();
            } catch (RuntimeException e) {
                LOG.warn("dispatch job poll failed; will retry after the poll interval", e);
                backOff = true;
            }
            if (backOff && !closed) {
                try {
                    Thread.sleep(config.pollInterval());
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /// The `fc_scheduler_*` series.
    public MultiCollector collector() {
        return poller.metrics().collector();
    }

    /// Exposed for tests.
    QueueMaintenance maintenance() {
        return maintenance;
    }

    /// Exposed for tests.
    PendingJobPoller poller() {
        return poller;
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        maintenance.close();
        poller.close();
    }
}

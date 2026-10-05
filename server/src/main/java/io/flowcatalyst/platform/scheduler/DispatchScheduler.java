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
/// [PendingJobPoller.PollResult#backOff]). [#close] stops the loop and the
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
    private final Thread thread;
    private volatile boolean closed;

    /// No stale-`QUEUED` recovery loop (owner ruling 2026-09-22,
    /// `docs/spec/router-hol-deferral.md` §Owner rulings): a job the broker
    /// holds is the broker's until the router delivers it. The 5-minute revert
    /// that used to run here re-published every message the router had
    /// deferred for a full pool — a second copy every 5 minutes for up to an
    /// hour — and the old PHP mediator it was written for is gone. A message
    /// the broker expires is simply gone; the reaper still redrives
    /// `PROCESSING` rows the mediator abandoned
    /// ([io.flowcatalyst.platform.dispatchjob.DispatchJobReaper], 15 min).
    /// Nothing needs recovering after a crash either: a job is `QUEUED` only
    /// once the broker has accepted it, and everything else is still `PENDING`.
    DispatchScheduler(PendingJobPoller poller, SchedulerConfig config) {
        this.poller = Objects.requireNonNull(poller, "poller");
        this.config = Objects.requireNonNull(config, "config");
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
        var scheduler = new DispatchScheduler(poller, config);
        scheduler.thread.start();
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
        poller.close();
    }
}

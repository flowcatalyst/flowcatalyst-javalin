package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/// The dispatch-job scheduler (dispatch-seam spec §3, §11, §12): owns
/// [PendingJobPoller]'s claim/publish loop and [StaleQueuedJobPoller]'s
/// recovery loop, ticking on independent [ScheduledExecutorService] tasks —
/// the same daemon-thread, `scheduleWithFixedDelay` pattern
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobReaper] already
/// established for this codebase's background subsystems. [#close] stops
/// both loops (CONVENTIONS §5: an explicit stop signal, no fire-and-forget
/// thread).
///
/// Cadence constants are `static final`, not env-driven — dispatch-seam spec
/// §3's timing-table note: Go's own `DefaultConfig` doc comment claims every
/// knob is env-overridable, but only `ProcessingEndpoint` (`processingEndpoint`
/// below, `FC_DISPATCH_PROCESSING_ENDPOINT`) actually is;
/// the owner question over the rest is still open, so this port keeps the
/// hardcoded defaults as the current spec.
public final class DispatchScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchScheduler.class);

    /// Poll cadence (spec §3 timing table `Config.PollInterval`).
    static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final PendingJobPoller poller;
    private final StaleQueuedJobPoller stale;
    private final ScheduledExecutorService executor;

    DispatchScheduler(PendingJobPoller poller, StaleQueuedJobPoller stale) {
        this.poller = Objects.requireNonNull(poller, "poller");
        this.stale = Objects.requireNonNull(stale, "stale");
        this.executor = Executors.newScheduledThreadPool(2, DispatchScheduler::daemonThread);
    }

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    private static Thread daemonThread(Runnable r) {
        Thread t = new Thread(r, "dispatch-scheduler-" + THREAD_COUNT.getAndIncrement());
        t.setDaemon(true);
        return t;
    }

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
                                           DispatchPublisher publisher, BooleanSupplier leader) {
        return start(appKey, processingEndpoint, pool, publisher, leader, PendingJobPoller.BATCH_SIZE);
    }

    /// Test-only: overrides the claim batch size, for the same reason
    /// [PendingJobPoller]'s package-private constructor does (CONVENTIONS §6,
    /// no truncation between tests).
    static DispatchScheduler start(String appKey, String processingEndpoint, DataSource pool,
                                    DispatchPublisher publisher, BooleanSupplier leader, int batchSize) {
        HmacTokenVerifier authVerifier;
        try {
            authVerifier = HmacTokenVerifier.fromAppKey(appKey);
        } catch (IllegalArgumentException e) {
            LOG.error("scheduler disabled: cannot derive dispatch-auth secret; set FLOWCATALYST_APP_KEY", e);
            return null;
        }
        var repository = new DispatchJobRepository(pool);
        var pausedCache = new PausedConnectionCache(pool);
        var poolCodes = new PoolCodeResolver(pool);
        var poller = new PendingJobPoller(pool, repository, pausedCache, poolCodes, publisher, authVerifier,
                processingEndpoint, leader, batchSize);
        var stale = new StaleQueuedJobPoller(repository, leader);
        var scheduler = new DispatchScheduler(poller, stale);
        scheduler.startLoops();
        LOG.atInfo().setMessage("dispatch scheduler started")
                .addKeyValue("interval", POLL_INTERVAL)
                .addKeyValue("stale_after", StaleQueuedJobPoller.STALE_AFTER)
                .log();
        return scheduler;
    }

    private void startLoops() {
        executor.scheduleWithFixedDelay(this::pollSafely, 0, POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::recoverSafely, 0,
                StaleQueuedJobPoller.SCAN_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            poller.pollOnce();
        } catch (RuntimeException e) {
            LOG.warn("dispatch job poll failed; will retry next tick", e);
        }
    }

    private void recoverSafely() {
        try {
            List<String> reverted = stale.recoverOnce();
            if (!reverted.isEmpty()) {
                LOG.debug("stale-queued recovery reverted {} job(s)", reverted.size());
            }
        } catch (RuntimeException e) {
            LOG.warn("stale-queued recovery failed; will retry next tick", e);
        }
    }

    /// Exposed for tests: one poll tick, synchronous.
    PendingJobPoller poller() {
        return poller;
    }

    /// Exposed for tests: one stale-recovery sweep, synchronous.
    StaleQueuedJobPoller staleQueuedJobPoller() {
        return stale;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

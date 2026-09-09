package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/// Recovers dispatch jobs stuck `QUEUED` (dispatch-seam spec §3 timing table
/// `StaleAfter`, §4): when the scheduler crashes between marking
/// `PENDING`→`QUEUED` and successfully publishing, or the broker drops a
/// message, the row stays `QUEUED` forever without this loop. Reverts any
/// row `QUEUED` longer than [#STALE_AFTER] back to `PENDING`, unconditionally
/// (no positional check — a stale `QUEUED` row means the normal claim/publish
/// path never finished for it, not that a sibling is holding it).
///
/// Leader-gated (spec §12) for the same reason as [PendingJobPoller]: this
/// runs alongside the claim loop under one active [DispatchScheduler], not
/// because reverting a stale row is itself order-sensitive.
public final class StaleQueuedJobPoller {

    private static final Logger LOG = LoggerFactory.getLogger(StaleQueuedJobPoller.class);

    /// How long a row may sit `QUEUED` before this loop reclaims it
    /// (dispatch-seam spec §3 timing table `Config.StaleAfter`).
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /// Sweep cadence (spec §3 timing table `Config.StaleScanInterval`).
    static final Duration SCAN_INTERVAL = Duration.ofSeconds(60);

    private final DispatchJobRepository repository;
    private final BooleanSupplier leader;
    private final Clock clock;
    private final Duration staleAfter;

    public StaleQueuedJobPoller(DispatchJobRepository repository, BooleanSupplier leader) {
        this(repository, leader, Clock.systemUTC(), STALE_AFTER);
    }

    StaleQueuedJobPoller(DispatchJobRepository repository, BooleanSupplier leader, Clock clock, Duration staleAfter) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
    }

    /// One sweep, exposed directly for tests: the ids reverted.
    public List<String> recoverOnce() {
        if (!leader.getAsBoolean()) {
            return List.of();
        }
        Instant cutoff = clock.instant().minus(staleAfter);
        List<String> reverted = repository.reclaimStaleQueued(cutoff);
        if (!reverted.isEmpty()) {
            LOG.atInfo().setMessage("stale-queued recovery reverted job(s) QUEUED→PENDING")
                    .addKeyValue("count", reverted.size())
                    .log();
        }
        return reverted;
    }
}

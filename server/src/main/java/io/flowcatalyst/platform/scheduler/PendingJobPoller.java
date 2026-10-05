package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.scheduler.jfr.ClaimedBatchEvent;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/// The scheduler's poller (dispatch-seam spec §3): READS the due `PENDING` jobs
/// and hands them to the dispatcher lanes ([DispatchLanes]), which publish them
/// and mark them `QUEUED`. **It never waits for a publish**: it blocks only when
/// the buffer is full (no permit left), i.e. when it is too far ahead of the
/// lanes.
///
/// One [#pollOnce]:
///
///  1. not the leader: nothing (the caller waits the poll interval);
///  2. acquire one permit (blocking), then up to `batchSize` in all without
///     blocking; `wanted` = permits held;
///  3. increment the claim generation, THEN snapshot the in-flight set, THEN
///     claim `LIMIT wanted` — one plain SELECT on `msg_dispatch_jobs`, no
///     transaction, no lock, no write — excluding paused subscriptions, the
///     groups found held in the last 5 seconds and this process's in-flight ids;
///  4. apply the `BLOCK_ON_ERROR` hold-back and the doomed check; the rows they
///     withhold are simply not submitted (still `PENDING`, claimed again);
///  5. hand the rest to the lanes (claim order, grouped -> `hash(group) % N`,
///     ungrouped -> round-robin) and release the permits not used.
///
/// A claimed job stays `PENDING` in the table and is kept out of the next claim
/// only by the in-memory in-flight set. A process that dies leaves nothing to
/// recover. A published-then-unmarked job is published again (the router drops a
/// copy whose original is in its pipeline, and `/api/dispatch/process` owns a
/// delivery only by winning the status-guarded
/// [DispatchJobLifecycle#claimForDelivery]).
///
/// ### Hold-back
///
/// One [DispatchJobRepository#heldBeforeIds] query over the candidates' distinct
/// groups, keyed by the EARLIEST holder per group, applied in memory. The
/// claimed list is iterated in claim order throughout, which is exactly the
/// order [DispatchPublisher] must preserve. A group found held is remembered for
/// 5 seconds ([HeldGroups]) so a batch-full of held rows at the head of the order
/// does not starve everything behind it.
///
/// Everything here runs on the scheduler's one poller thread.
public final class PendingJobPoller implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PendingJobPoller.class);

    /// Max rows per claim (spec §3 timing table `Config.BatchSize`).
    static final int BATCH_SIZE = SchedulerConfig.DEFAULT_BATCH_SIZE;

    private final DispatchJobRepository repository;
    private final DispatchJobLifecycle lifecycle;
    private final PausedConnectionCache pausedCache;
    private final PoolCodeResolver poolCodes;
    private final HmacTokenVerifier authVerifier;
    private final String processingEndpoint;
    private final BooleanSupplier leader;
    private final SchedulerConfig config;
    private final SchedulerMetrics metrics;
    private final DispatchLanes lanes;

    /// At most one starvation warning a minute ([#warnIfStarved]); touched
    /// only by the poller thread.
    private static final long STARVED_WARN_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private boolean warnedStarved;
    private long lastStarvedWarnNanos;
    /// Groups found held back in the last 5 seconds, skipped by the claim (poller thread only; its size is
    /// read by the metrics gauge, so the count is mirrored in a volatile).
    private final HeldGroups heldGroups = new HeldGroups();
    private volatile int heldGroupCount;
    /// Builds the poller and starts its lanes.
    public PendingJobPoller(DataSource dataSource, DispatchJobRepository repository, DispatchJobLifecycle lifecycle,
                             PausedConnectionCache pausedCache, PoolCodeResolver poolCodes,
                             DispatchPublisher publisher, HmacTokenVerifier authVerifier,
                             String processingEndpoint, BooleanSupplier leader, SchedulerConfig config) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.pausedCache = Objects.requireNonNull(pausedCache, "pausedCache");
        this.poolCodes = Objects.requireNonNull(poolCodes, "poolCodes");
        this.authVerifier = Objects.requireNonNull(authVerifier, "authVerifier");
        this.processingEndpoint = Objects.requireNonNull(processingEndpoint, "processingEndpoint");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = new SchedulerMetrics(config.dispatchers());
        this.metrics.heldGroups(() -> heldGroupCount);
        this.lanes = new DispatchLanes(config, Objects.requireNonNull(lifecycle, "lifecycle"), Objects.requireNonNull(publisher, "publisher"),
                this::buildMessage, metrics);
        this.lanes.start();
    }

    /// What one [#pollOnce] did, so the caller can decide whether to wait.
    ///
    /// @param claimed   rows the claim returned
    /// @param submitted rows handed to a lane (claimed less held back)
    /// @param wanted    rows the claim asked for (the permits it held)
    /// @param heldBack  rows left `PENDING` behind a `BLOCK_ON_ERROR` hold-back
    /// @param backOff   wait the poll interval before the next claim: the claim
    ///                  came back short (nothing more is waiting), or nothing
    ///                  was submitted (all held), or a lane reported a failure
    ///                  since the previous claim (without the pause a failing
    ///                  broker is retried in a hot loop, because failed rows
    ///                  are still `PENDING`), or this is not the leader
    public record PollResult(int claimed, int submitted, int wanted, int heldBack, boolean backOff) {
        /// Not the leader, or nothing to do.
        static final PollResult IDLE = new PollResult(0, 0, 0, 0, true);
        /// Interrupted while waiting for a permit: the caller is shutting down.
        static final PollResult INTERRUPTED = new PollResult(0, 0, 0, 0, false);
    }

    /// Runs one poll. Only the leader claims (spec §12) — a non-leader poll is
    /// a no-op ([PollResult#IDLE]), not an error. Blocks while the buffer is
    /// full. A claim failure releases what it held and throws.
    public PollResult pollOnce() {
        if (!leader.getAsBoolean()) {
            return PollResult.IDLE;
        }
        int wanted;
        try {
            wanted = lanes.acquirePermits(config.batchSize());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PollResult.INTERRUPTED;
        }
        // The wait may have been long: leadership can have gone meanwhile.
        if (!leader.getAsBoolean()) {
            lanes.releasePermits(wanted);
            return PollResult.IDLE;
        }
        Claimed claimed;
        long generation;
        var event = new ClaimedBatchEvent();
        event.begin();
        long startNanos = System.nanoTime();
        int inFlightCount;
        try {
            // Generation FIRST, then the snapshot (DispatchLanes class doc).
            generation = lanes.nextGeneration();
            DispatchLanes.Snapshot inFlight = lanes.inFlightSnapshot();
            inFlightCount = inFlight.ids().size();
            Set<String> paused = pausedCache.pausedSubscriptionIds();
            claimed = claim(wanted, paused, inFlight);
        } catch (RuntimeException e) {
            metrics.pollErrors.increment();
            lanes.releasePermits(wanted);
            throw e;
        }
        metrics.claimNanos.add(System.nanoTime() - startNanos);
        metrics.claimCount.increment();

        lanes.submit(claimed.toSubmit(), generation);
        lanes.releasePermits(wanted - claimed.toSubmit().size());

        int submitted = claimed.toSubmit().size();
        metrics.claimed.add(claimed.claimedCount());
        metrics.submitted.add(submitted);
        metrics.skippedHeld.add(claimed.heldBack());
        metrics.skippedDoomed.add(claimed.doomedSkipped());
        boolean full = claimed.claimedCount() >= wanted;
        if (full) metrics.fullBatchClaims.increment();
        if (event.shouldCommit()) {
            event.wanted = wanted;
            event.size = claimed.claimedCount();
            event.submitted = submitted;
            event.heldBack = claimed.heldBack();
            event.inFlight = inFlightCount;
            event.commit();
        }
        warnIfStarved(claimed, wanted, submitted);
        boolean laneFailed = lanes.takeFailure();
        // Rows left behind a doomed job are claimed again in moments: not a reason to sleep.
        boolean backOff = !full || (submitted == 0 && claimed.doomedSkipped() == 0) || laneFailed;
        return new PollResult(claimed.claimedCount(), submitted, wanted, claimed.heldBack(), backOff);
    }

    /// The result of one claim + hold-back step.
    private record Claimed(int claimedCount, List<DispatchJobRepository.ClaimRow> toSubmit, int heldBack,
                           int doomedSkipped) {
    }

    private Claimed claim(int wanted, Set<String> paused, DispatchLanes.Snapshot inFlight) {
        List<DispatchJobRepository.ClaimRow> claims =
                repository.claimPending(wanted, paused, heldGroups.current(), inFlight.ids());
        heldGroupCount = heldGroups.size();
        if (claims.isEmpty()) {
            return new Claimed(0, List.of(), 0, 0);
        }
        // The claim excludes this process's in-flight ids, so a row already in flight cannot come back; if one
        // does (a bug, or a lane that has not yet removed it) it is dropped and counted, never submitted twice.
        List<DispatchJobRepository.ClaimRow> fresh = new ArrayList<>(claims.size());
        for (DispatchJobRepository.ClaimRow c : claims) {
            if (lanes.inFlightContains(c.id())) {
                metrics.alreadyInFlight.increment();
            } else {
                fresh.add(c);
            }
        }
        if (fresh.isEmpty()) return new Claimed(claims.size(), List.of(), 0, 0);
        Claimed r = claimRest(fresh, inFlight);
        return new Claimed(claims.size(), r.toSubmit(), r.heldBack(), r.doomedSkipped());
    }

    /// The hold-back and the doomed check over claimed rows none of which is already in flight. Rows they
    /// withhold are simply not submitted: they are still `PENDING` and are claimed again.
    private Claimed claimRest(List<DispatchJobRepository.ClaimRow> claims, DispatchLanes.Snapshot inFlight) {
        List<DispatchJobRepository.ClaimRow> toSubmit = new ArrayList<>(claims.size());
        int heldBack = 0;
        // One query for every BLOCK_ON_ERROR candidate's positional hold-back (not one per candidate).
        List<DispatchJobRepository.ClaimRow> blockCandidates = new ArrayList<>();
        for (DispatchJobRepository.ClaimRow c : claims) {
            if (c.mode() == DispatchMode.BLOCK_ON_ERROR) {
                blockCandidates.add(c);
            }
        }
        Set<String> held = blockCandidates.isEmpty() ? Set.of() : repository.heldBeforeIds(blockCandidates);
        Set<String> heldGroupNames = new java.util.HashSet<>();
        for (DispatchJobRepository.ClaimRow c : claims) {
            if (held.contains(c.id())) {
                heldBack++;
                heldGroupNames.add(c.messageGroup()); // positional hold-back, spec §3 "GroupHolding"
                continue;
            }
            toSubmit.add(c);
        }
        heldGroups.remember(heldGroupNames);
        heldGroupCount = heldGroups.size();
        // Jobs of this process still in flight were skipped by the claim; if one of them is DOOMED (a lane will
        // drop it), the rows behind it in its group must wait (DispatchLanes, point 4).
        List<DispatchJobRepository.ClaimRow> passed = lanes.withoutGroupsBehindDoomedJobs(toSubmit, inFlight);
        return new Claimed(claims.size(), passed, heldBack, toSubmit.size() - passed.size());
    }

    /// A full claim that submits nothing is the signature of starvation: the
    /// first rows in claim order are all held back (a `BLOCK_ON_ERROR` group
    /// behind a failed head), so every claim returns the same rows and
    /// whatever sorts behind them is never reached — silently. Warns, at most
    /// once a minute, with the counts that say why.
    private void warnIfStarved(Claimed claimed, int wanted, int submitted) {
        if (claimed.claimedCount() < wanted || submitted > 0 || claimed.doomedSkipped() > 0) {
            return;
        }
        long now = System.nanoTime();
        if (warnedStarved && now - lastStarvedWarnNanos < STARVED_WARN_INTERVAL_NANOS) {
            return;
        }
        warnedStarved = true;
        lastStarvedWarnNanos = now;
        LOG.atWarn().setMessage("a full claim submitted nothing; queued jobs behind these rows are not being "
                        + "reached until the held rows move")
                .addKeyValue("claimed", claimed.claimedCount())
                .addKeyValue("heldSkipped", claimed.heldBack())
                .log();
    }

    /// Resolves a claimed row to the exact wire [Message] the router
    /// contract requires (dispatch-seam spec §2). `authToken` is
    /// double-duty (spec §2): the same token both authenticates the
    /// `/api/dispatch/process` callback AND is what the router forwards to
    /// `/api/dispatch/settled` for an ACKed sibling. Runs on a lane.
    private PublishedMessage buildMessage(DispatchJobRepository.ClaimRow c) {
        String poolCode = poolCodes.resolve(c.dispatchPoolId(), c.clientId());
        String authToken = authVerifier.sign(c.id());
        String groupId = (c.messageGroup() == null || c.messageGroup().isEmpty()) ? null : c.messageGroup();
        Message message = new Message(c.id(), poolCode, authToken, null,
                MediationType.HTTP, processingEndpoint, groupId, false, c.mode());
        return new PublishedMessage(c.id(), c.createdAt(), c.clientId(), c.subscriptionId(), c.queue(), message);
    }

    public SchedulerMetrics metrics() {
        return metrics;
    }

    /// Test seam: waits up to `timeout` until every claimed job has been
    /// settled by a lane (published, left `PENDING`, or dropped) and every
    /// permit is back.
    boolean awaitIdle(Duration timeout) {
        return lanes.awaitIdle(timeout);
    }

    HeldGroups heldGroupsForTest() {
        return heldGroups;
    }

    DispatchLanes lanes() {
        return lanes;
    }

    /// Stops the lanes (each finishes the batch it is sending, then exits).
    @Override
    public void close() {
        lanes.close();
    }
}

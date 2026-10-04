package io.flowcatalyst.platform.scheduler;

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

/// The scheduler's poller (dispatch-seam spec §3): claims `PENDING` rows and
/// hands them to the dispatcher lanes ([DispatchLanes]), which publish them
/// and mark them `QUEUED`. **It never waits for a publish**: it blocks only
/// when the buffer is full (no permit left), i.e. when it is too far ahead of
/// the lanes.
///
/// One [#pollOnce]:
///
///  1. not the leader: nothing (the caller waits the poll interval);
///  2. acquire one permit (blocking), then up to `batchSize` in all without
///     blocking; `wanted` = permits held;
///  3. increment the claim generation, THEN snapshot the in-flight ids, THEN
///     claim `LIMIT wanted` excluding paused subscriptions and the in-flight
///     ids — one plain statement, no transaction, no row lock;
///  4. apply the `BLOCK_ON_ERROR` hold-back (rows held stay `PENDING`);
///  5. hand the rest to the lanes (claim order, grouped -> `hash(group) % N`,
///     ungrouped -> round-robin) and release the permits not used.
///
/// ### Why there is no transaction around the claim any more
///
/// A claim that holds row locks across the broker round-trips (ten
/// `SendMessageBatch` calls for a full SQS batch) serialises the whole
/// scheduler behind its slowest publish. The rows a claim returns are kept out
/// of the NEXT claim by the in-flight id set instead, which costs one array
/// parameter. Two outcomes that the locks used to prevent are now simply
/// accepted, because both were already harmless: a job published and then
/// returned to `PENDING` by a failed status update is published again (the
/// router drops a copy whose original is in its pipeline, and
/// `/api/dispatch/process` owns a delivery only by winning the status-guarded
/// [DispatchJobRepository#claimForDelivery], so the copy that arrives second
/// finds the job moved on and is acked without calling the subscriber); and a
/// copy can reach `/process` while the row is still `PENDING`, which
/// `claimForDelivery` accepts (`PENDING`/`QUEUED` -> `PROCESSING`), after
/// which the lane's status-guarded `QUEUED` update finds no `PENDING` row and
/// changes nothing.
///
/// ### Hold-back and paused subscriptions
///
/// The hold-back check is one [DispatchJobRepository#heldBeforeIds] query over
/// the candidates' distinct groups, keyed by the EARLIEST holder per group,
/// then applied in memory — "is ANY holder positioned before me" and "is the
/// earliest holder positioned before me" are the same question. The claimed
/// list is iterated in claim order throughout, which is exactly the order
/// [DispatchPublisher] must preserve. Paused subscriptions are excluded by the
/// claim query itself (they used to be filtered a second time in memory, a
/// check that could never fire and is gone).
///
/// Everything here runs on the scheduler's one poller thread.
public final class PendingJobPoller implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PendingJobPoller.class);

    /// Max rows per claim (spec §3 timing table `Config.BatchSize`).
    static final int BATCH_SIZE = SchedulerConfig.DEFAULT_BATCH_SIZE;

    private final DispatchJobRepository repository;
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

    /// Builds the poller and starts its lanes.
    public PendingJobPoller(DataSource dataSource, DispatchJobRepository repository,
                             PausedConnectionCache pausedCache, PoolCodeResolver poolCodes,
                             DispatchPublisher publisher, HmacTokenVerifier authVerifier,
                             String processingEndpoint, BooleanSupplier leader, SchedulerConfig config) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.pausedCache = Objects.requireNonNull(pausedCache, "pausedCache");
        this.poolCodes = Objects.requireNonNull(poolCodes, "poolCodes");
        this.authVerifier = Objects.requireNonNull(authVerifier, "authVerifier");
        this.processingEndpoint = Objects.requireNonNull(processingEndpoint, "processingEndpoint");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = new SchedulerMetrics(config.dispatchers());
        this.lanes = new DispatchLanes(config, repository, Objects.requireNonNull(publisher, "publisher"),
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
        int excluded;
        try {
            // Generation FIRST, then the snapshot (DispatchLanes class doc).
            generation = lanes.nextGeneration();
            DispatchLanes.Snapshot inFlight = lanes.inFlightSnapshot();
            excluded = inFlight.ids().size();
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
            event.inFlight = excluded;
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
        List<DispatchJobRepository.ClaimRow> claims = repository.claimPending(wanted, paused, inFlight.ids());
        if (claims.isEmpty()) {
            return new Claimed(0, List.of(), 0, 0);
        }
        List<DispatchJobRepository.ClaimRow> toSubmit = new ArrayList<>(claims.size());
        // One query for every BLOCK_ON_ERROR candidate's positional hold-back
        // (not one per candidate).
        List<DispatchJobRepository.ClaimRow> blockCandidates = new ArrayList<>();
        for (DispatchJobRepository.ClaimRow c : claims) {
            if (c.mode() == DispatchMode.BLOCK_ON_ERROR) {
                blockCandidates.add(c);
            }
        }
        Set<String> held = blockCandidates.isEmpty() ? Set.of() : repository.heldBeforeIds(blockCandidates);
        int heldBack = 0;
        for (DispatchJobRepository.ClaimRow c : claims) {
            if (held.contains(c.id())) {
                heldBack++;
                continue; // positional hold-back — left PENDING, spec §3 "GroupHolding"
            }
            toSubmit.add(c);
        }
        // The claim excluded in-flight jobs; if one of them is DOOMED (a lane will
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
        LOG.atWarn().setMessage("a full claim submitted nothing; PENDING jobs behind these rows are not being "
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

    DispatchLanes lanes() {
        return lanes;
    }

    /// Stops the lanes (each finishes the batch it is sending, then exits).
    @Override
    public void close() {
        lanes.close();
    }
}

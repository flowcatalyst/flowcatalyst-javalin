package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.scheduler.jfr.ClaimedBatchEvent;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/// One poll tick = one transaction (dispatch-seam spec §3): claim `PENDING`
/// rows `FOR UPDATE SKIP LOCKED`, filter (paused subscription, then the
/// positional `BLOCK_ON_ERROR` hold-back), **publish while the claim's row
/// locks are held, mark `QUEUED` exactly the jobs the broker accepted, then
/// commit**.
///
/// ### Why publish before commit (review 2026-09-28)
///
/// The previous order — mark `QUEUED`, commit, then publish — had a window:
/// a process death between the commit and the publish left jobs `QUEUED`
/// that never reached the broker. Nothing recovers a `QUEUED` row (owner
/// ruling 2026-09-22 removed the stale sweep: a broker-held job is the
/// broker's), so they stayed that way for ever.
///
/// Publishing first makes `QUEUED` mean what the ruling assumes it means:
/// **the broker accepted this job**. There is no window in which a row is
/// `QUEUED` without a message, so there is still nothing to sweep, and a job
/// the broker holds is still never re-sent because of its status.
///
/// The price is the case Go's order was avoiding: the publish succeeds and
/// the commit then fails (or the process dies before it). The rows roll back
/// to `PENDING` with a copy already at the broker, and the next tick
/// publishes them again. That second copy is harmless: `/api/dispatch/process`
/// owns a delivery only by winning the status-guarded
/// [DispatchJobRepository#claimForDelivery] (`PENDING`/`QUEUED` →
/// `PROCESSING`), so whichever copy arrives second finds the job
/// `PROCESSING` or terminal and is acked without calling the subscriber. A
/// duplicate publish costs one extra queue message; a stranded row cost the
/// job.
///
/// Two consequences worth knowing:
/// - A copy can reach `/process` before this transaction commits. Its
///   status-guarded `UPDATE` waits on the claim's row lock and, at commit,
///   re-reads the row (`QUEUED` → it wins the claim, as usual).
/// - The claim's row locks are held across the broker round-trips (for SQS,
///   ten `SendMessageBatch` calls for a full batch). Only the leader claims,
///   and every other writer of these rows is status-guarded and short, so
///   the cost is a brief wait, not contention.
///
/// A partial publish failure (ruling O2) needs no revert any more: the jobs
/// the publisher reports unpublished are simply not marked, and stay
/// `PENDING` for the next tick.
///
/// Simplification versus Go's `pollOnce` (`poller.go:147-309`), not a
/// behavioural change: Go batches the hold-back check into one
/// `blockedGroups` query keyed by the EARLIEST holder per candidate group,
/// because its claim loop needed a `map[group]jobKey` up front. This class
/// instead asks [DispatchJobRepository#groupHeldBefore(String,int,Instant,String)]
/// once per `BLOCK_ON_ERROR` candidate — "is ANY holder positioned before
/// me" rather than "is the earliest holder positioned before me". The two
/// are logically equivalent (a holder positioned before me exists iff the
/// earliest one does), and the claim query's own `ORDER BY message_group,
/// sequence, created_at, id` already interleaves groups correctly, so no
/// separate grouping pass is needed either — the claimed list is iterated
/// in claim order throughout, which is exactly the order [DispatchPublisher]
/// must preserve.
public final class PendingJobPoller {

    private static final Logger LOG = LoggerFactory.getLogger(PendingJobPoller.class);

    /// Batch size and claim-tx row-lock bound (dispatch-seam spec §3 timing
    /// table `Config.BatchSize`). Not env-driven — spec §3's timing-table
    /// note flags the Go doc comment claiming otherwise as stale; the owner
    /// question is open, so this stays a hardcoded default per current spec.
    static final int BATCH_SIZE = 100;

    private final DataSource dataSource;
    private final DispatchJobRepository repository;
    private final PausedConnectionCache pausedCache;
    private final PoolCodeResolver poolCodes;
    private final DispatchPublisher publisher;
    private final HmacTokenVerifier authVerifier;
    private final String processingEndpoint;
    private final BooleanSupplier leader;
    private final int batchSize;

    public PendingJobPoller(DataSource dataSource, DispatchJobRepository repository,
                             PausedConnectionCache pausedCache, PoolCodeResolver poolCodes,
                             DispatchPublisher publisher, HmacTokenVerifier authVerifier,
                             String processingEndpoint, BooleanSupplier leader) {
        this(dataSource, repository, pausedCache, poolCodes, publisher, authVerifier, processingEndpoint,
                leader, BATCH_SIZE);
    }

    /// Test-only: overrides the claim batch size. The embedded test database
    /// is never truncated between test classes (CONVENTIONS §6), so a poller
    /// test seeding a handful of its own rows needs a batch large enough that
    /// unrelated `PENDING` rows other test classes left behind cannot crowd
    /// them out of `LIMIT`.
    PendingJobPoller(DataSource dataSource, DispatchJobRepository repository,
                      PausedConnectionCache pausedCache, PoolCodeResolver poolCodes,
                      DispatchPublisher publisher, HmacTokenVerifier authVerifier,
                      String processingEndpoint, BooleanSupplier leader, int batchSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.pausedCache = Objects.requireNonNull(pausedCache, "pausedCache");
        this.poolCodes = Objects.requireNonNull(poolCodes, "poolCodes");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.authVerifier = Objects.requireNonNull(authVerifier, "authVerifier");
        this.processingEndpoint = Objects.requireNonNull(processingEndpoint, "processingEndpoint");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.batchSize = batchSize;
    }

    /// What one tick did, so the scheduler can decide whether to tick again
    /// at once.
    ///
    /// @param claimed   rows the claim returned
    /// @param published rows the broker accepted (marked `QUEUED`, committed)
    /// @param full      the claim filled the whole batch, so more rows may be waiting
    public record PollResult(int claimed, int published, boolean full) {
        static final PollResult IDLE = new PollResult(0, 0, false);

        /// Tick again without sleeping: a full claim means a backlog, and
        /// `published > 0` means it is draining. A full claim that published
        /// nothing (everything held back, or the broker refusing) would claim
        /// the same rows again, so it must wait for the ordinary delay.
        public boolean drainImmediately() {
            return full && published > 0;
        }
    }

    /// Runs one tick. Only the leader claims (spec §12) — a non-leader tick
    /// is a no-op ([PollResult#IDLE]), not an error.
    public PollResult pollOnce() {
        if (!leader.getAsBoolean()) {
            return PollResult.IDLE;
        }
        Set<String> paused = pausedCache.pausedSubscriptionIds();
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            Claimed claimed;
            List<String> queued;
            try {
                DbTx tx = DbTx.wrapForBootstrap(conn);
                claimed = claim(tx, paused);
                queued = publishAndMark(tx, claimed.toPublish());
            } catch (RuntimeException e) {
                // Anything published before this point redelivers as a
                // harmless duplicate once the rows are claimed again (class
                // doc); nothing is left QUEUED without a message.
                rollbackQuietly(conn);
                throw e;
            }
            commit(conn, queued);
            // After the commit, never before: an event for a write that then
            // rolls back is a lie in the recording (docs/spec/jfr-events.md).
            recordBatch(claimed, queued.size());
            return new PollResult(claimed.claimedCount(), queued.size(), claimed.claimedCount() >= batchSize);
        } catch (SQLException e) {
            throw new PollFailedException(e);
        }
    }

    /// The claim's transaction is [DbTx#wrapForBootstrap] — the sanctioned
    /// escape hatch for exactly this shape: an externally managed,
    /// multi-statement transaction outside the use-case envelope. This is
    /// router-driven infrastructure, not a human-initiated command, so it has
    /// no `Operation`/`UnitOfWork` to run inside (mirroring the repository's
    /// own "infra writes" section, which bypasses the envelope for the same
    /// reason).
    private void commit(Connection conn, List<String> queued) throws SQLException {
        try {
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (!queued.isEmpty()) {
                LOG.atWarn().setMessage("claim commit failed after publishing; the jobs stay PENDING and will be "
                                + "published again, and /process discards whichever copy arrives second")
                        .addKeyValue("count", queued.size())
                        .setCause(e)
                        .log();
            }
            throw e;
        }
    }

    /// The result of one claim+filter step.
    private record Claimed(int claimedCount, List<DispatchJobRepository.ClaimRow> toPublish, int heldBack) {
        private static final Claimed EMPTY = new Claimed(0, List.of(), 0);
    }

    /// Claims and filters inside the caller's transaction (spec §3, steps
    /// 2-3). Marks nothing: `QUEUED` waits for the broker ([#publishAndMark]).
    private Claimed claim(DbTx tx, Set<String> paused) {
        List<DispatchJobRepository.ClaimRow> claims = repository.claimPending(tx, batchSize);
        if (claims.isEmpty()) {
            return Claimed.EMPTY;
        }
        List<DispatchJobRepository.ClaimRow> toPublish = new ArrayList<>(claims.size());
        int heldBack = 0;
        for (DispatchJobRepository.ClaimRow c : claims) {
            if (c.subscriptionId() != null && paused.contains(c.subscriptionId())) {
                continue; // paused-subscription filter (spec §3, step 3) — left PENDING
            }
            if (c.mode() == DispatchMode.BLOCK_ON_ERROR
                    && repository.groupHeldBefore(c.messageGroup(), c.sequence(), c.createdAt(), c.id())) {
                heldBack++;
                continue; // positional hold-back — left PENDING, spec §3 "GroupHolding"
            }
            toPublish.add(c);
        }
        return new Claimed(claims.size(), toPublish, heldBack);
    }

    /// Publishes the survivors and marks `QUEUED` exactly those the broker
    /// accepted, in the claim's transaction.
    ///
    /// @return the ids marked `QUEUED`
    private List<String> publishAndMark(DbTx tx, List<DispatchJobRepository.ClaimRow> toPublish) {
        if (toPublish.isEmpty()) {
            return List.of();
        }
        List<PublishedMessage> batch = toPublish.stream().map(this::buildMessage).toList();
        Set<String> unpublished = Set.of();
        try {
            publisher.publish(batch);
        } catch (DispatchPublisher.PublishException e) {
            // Ruling O2: exactly the jobs the publisher reports unpublished
            // stay PENDING. A job it omits was accepted by the broker and is
            // legitimately QUEUED; leaving that one PENDING too would publish
            // it again next tick.
            unpublished = Set.copyOf(e.unpublishedJobIds());
            LOG.atWarn().setMessage("batch publish failed; the unpublished job(s) stay PENDING for the next tick")
                    .addKeyValue("count", unpublished.size())
                    .addKeyValue("claimed", batch.size())
                    .setCause(e)
                    .log();
        }
        List<String> ids = new ArrayList<>(toPublish.size());
        Instant minCreated = null;
        Instant maxCreated = null;
        for (DispatchJobRepository.ClaimRow c : toPublish) {
            if (unpublished.contains(c.id())) {
                continue;
            }
            ids.add(c.id());
            if (minCreated == null || c.createdAt().isBefore(minCreated)) minCreated = c.createdAt();
            if (maxCreated == null || c.createdAt().isAfter(maxCreated)) maxCreated = c.createdAt();
        }
        if (!ids.isEmpty()) {
            repository.markQueued(tx, ids, minCreated, maxCreated);
        }
        return ids;
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            LOG.warn("poll transaction rollback failed", rollbackFailure);
        }
    }

    /// @param published how many the broker accepted — marked `QUEUED` and
    ///                  committed; a partial publish failure makes it less
    ///                  than the survivors of the filter
    private void recordBatch(Claimed claimed, int published) {
        if (claimed.claimedCount() == 0) {
            return;
        }
        var event = new ClaimedBatchEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.size = claimed.claimedCount();
        event.published = published;
        event.heldBack = claimed.heldBack();
        event.commit();
    }

    /// Resolves a claimed row to the exact wire [Message] the router
    /// contract requires (dispatch-seam spec §2). `authToken` is
    /// double-duty (spec §2): the same token both authenticates the
    /// `/api/dispatch/process` callback AND is what the router forwards to
    /// `/api/dispatch/settled` for an ACKed sibling.
    private PublishedMessage buildMessage(DispatchJobRepository.ClaimRow c) {
        String poolCode = poolCodes.resolve(c.dispatchPoolId(), c.clientId());
        String authToken = authVerifier.sign(c.id());
        String groupId = (c.messageGroup() == null || c.messageGroup().isEmpty()) ? null : c.messageGroup();
        Message message = new Message(c.id(), poolCode, authToken, null,
                MediationType.HTTP, processingEndpoint, groupId, false, c.mode());
        return new PublishedMessage(c.id(), c.createdAt(), c.clientId(), c.subscriptionId(), c.queue(), message);
    }

    /// Wraps a claim-transaction JDBC failure — connection acquisition or
    /// commit (the claim/mark-QUEUED statements throw jOOQ's own unchecked
    /// exception). Unchecked:
    /// [DispatchScheduler]'s tick loop catches `RuntimeException` and retries
    /// on the next tick, exactly like [io.flowcatalyst.platform.dispatchjob.DispatchJobReaper].
    static final class PollFailedException extends RuntimeException {
        PollFailedException(SQLException cause) {
            super("dispatch job poll failed", cause);
        }
    }
}

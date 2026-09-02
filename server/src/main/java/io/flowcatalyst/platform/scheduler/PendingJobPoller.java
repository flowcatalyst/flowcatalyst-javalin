package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.scheduler.jfr.ClaimedBatchEvent;
import io.flowcatalyst.platform.subscription.DispatchMode;
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
/// positional `BLOCK_ON_ERROR` hold-back), mark the survivors `QUEUED`,
/// commit, THEN publish — a publish failure reverts the whole batch.
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

    /// Runs one tick. Only the leader claims (spec §12) — a non-leader tick
    /// is a no-op, not an error.
    public void pollOnce() {
        if (!leader.getAsBoolean()) {
            return;
        }
        Set<String> paused = pausedCache.pausedSubscriptionIds();
        Claimed claimed = claimAndMark(paused);
        recordBatch(claimed);
        if (claimed.toPublish().isEmpty()) {
            return;
        }
        List<PublishedMessage> batch = claimed.toPublish().stream().map(this::buildMessage).toList();
        try {
            publisher.publish(batch);
        } catch (DispatchPublisher.PublishException e) {
            List<String> ids = claimed.toPublish().stream().map(DispatchJobRepository.ClaimRow::id).toList();
            LOG.warn("batch publish failed; reverting {} job(s) QUEUED→PENDING", ids.size(), e);
            repository.revertQueuedToPending(ids);
        }
    }

    /// The result of one claim+filter+mark-QUEUED transaction.
    private record Claimed(int claimedCount, List<DispatchJobRepository.ClaimRow> toPublish, int heldBack) {
        private static final Claimed EMPTY = new Claimed(0, List.of(), 0);
    }

    /// Claims, filters and marks QUEUED inside one JDBC transaction (spec §3,
    /// steps 2-4), committing before returning. [DbTx#wrapForBootstrap] is
    /// the sanctioned escape hatch for exactly this shape: an externally
    /// managed, multi-statement transaction outside the use-case envelope —
    /// this is router-driven infrastructure, not a human-initiated command,
    /// so it has no `Operation`/`UnitOfWork` to run inside (mirroring the
    /// repository's own "infra writes" section, which bypasses the envelope
    /// for the same reason).
    private Claimed claimAndMark(Set<String> paused) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Claimed result = claimAndMarkInTx(DbTx.wrapForBootstrap(conn), paused);
                conn.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new PollFailedException(e);
        }
    }

    private Claimed claimAndMarkInTx(DbTx tx, Set<String> paused) {
        List<DispatchJobRepository.ClaimRow> claims = repository.claimPending(tx, batchSize);
        if (claims.isEmpty()) {
            return Claimed.EMPTY;
        }
        List<DispatchJobRepository.ClaimRow> toPublish = new ArrayList<>(claims.size());
        List<String> ids = new ArrayList<>(claims.size());
        Instant minCreated = null;
        Instant maxCreated = null;
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
            ids.add(c.id());
            if (minCreated == null || c.createdAt().isBefore(minCreated)) minCreated = c.createdAt();
            if (maxCreated == null || c.createdAt().isAfter(maxCreated)) maxCreated = c.createdAt();
        }
        if (!ids.isEmpty()) {
            repository.markQueued(tx, ids, minCreated, maxCreated);
        }
        return new Claimed(claims.size(), toPublish, heldBack);
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            LOG.warn("poll transaction rollback failed", rollbackFailure);
        }
    }

    private void recordBatch(Claimed claimed) {
        if (claimed.claimedCount() == 0) {
            return;
        }
        var event = new ClaimedBatchEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.size = claimed.claimedCount();
        event.published = claimed.toPublish().size();
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
                MediationType.HTTP, processingEndpoint, groupId, false, toWireMode(c.mode()));
        return new PublishedMessage(c.id(), c.createdAt(), message);
    }

    /// `io.flowcatalyst.platform.subscription.DispatchMode` (the stored
    /// value's parse target) and `io.flowcatalyst.router.wire.DispatchMode`
    /// (the wire enum [Message] carries) are two distinct types sharing one
    /// simple name — package-qualified here rather than imported, the one
    /// case CONVENTIONS §8's "import it" cannot satisfy.
    private static io.flowcatalyst.router.wire.DispatchMode toWireMode(DispatchMode mode) {
        return switch (mode) {
            case IMMEDIATE -> io.flowcatalyst.router.wire.DispatchMode.IMMEDIATE;
            case NEXT_ON_ERROR -> io.flowcatalyst.router.wire.DispatchMode.NEXT_ON_ERROR;
            case BLOCK_ON_ERROR -> io.flowcatalyst.router.wire.DispatchMode.BLOCK_ON_ERROR;
        };
    }

    /// Wraps a claim-transaction JDBC failure — connection acquisition,
    /// commit, or the claim/mark-QUEUED statements themselves. Unchecked:
    /// [DispatchScheduler]'s tick loop catches `RuntimeException` and retries
    /// on the next tick, exactly like [io.flowcatalyst.platform.dispatchjob.DispatchJobReaper].
    static final class PollFailedException extends RuntimeException {
        PollFailedException(SQLException cause) {
            super("dispatch job poll failed", cause);
        }
    }
}

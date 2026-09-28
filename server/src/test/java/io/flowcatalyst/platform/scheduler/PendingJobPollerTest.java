package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.jooq.Record;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.BooleanSupplier;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [PendingJobPoller] against a real embedded Postgres (dispatch-seam spec
/// §2, §3): every field of the published message and its round trip through
/// the router's own [Message] parser, the claim-time `BLOCK_ON_ERROR`
/// hold-back's positional semantics, publish failure leaving the batch
/// `PENDING`, the publish-before-commit order that leaves no row `QUEUED`
/// without a message, the
/// paused-connection filter, leader-gating, and the claim query's total
/// order. A large test-only batch size (see [#poller]) keeps these robust
/// against the `PENDING` rows other test classes leave behind — CONVENTIONS
/// §6, no truncation between tests.
class PendingJobPollerTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);
    private static final String APP_KEY = "test-app-key-" + RUN;
    private static final HmacTokenVerifier AUTH = HmacTokenVerifier.fromAppKey(APP_KEY);
    private static final String PROCESSING_ENDPOINT = "http://localhost:18080/api/dispatch/process";
    /// Postgres/no-prefix (dev-shaped) settings — [PostgresQueuePublisher]
    /// now routes per (tenant, priority) rather than to one fixed queue name
    /// (`docs/spec/deployed-dispatch.md` §3, unit D part 1), so
    /// [#queueRow(String)] looks a row up by id alone; which composed queue
    /// name it actually landed in is pinned by `PostgresQueuePublisherTest`.
    private static final DispatchQueueSettings SETTINGS = new DispatchQueueSettings(false, "", "", "", "");

    @BeforeAll
    static void initQueueSchema() {
        PostgresQueue.initSchema(DATA_SOURCE);
    }

    private static PendingJobPoller poller(DispatchPublisher publisher, BooleanSupplier leader) {
        return new PendingJobPoller(DATA_SOURCE, REPO, new PausedConnectionCache(DATA_SOURCE),
                new PoolCodeResolver(DATA_SOURCE), publisher, AUTH, PROCESSING_ENDPOINT, leader, 5000);
    }

    private static Record queueRow(String id) {
        return DB.fetchOne("SELECT * FROM queue_messages WHERE id = ?", id);
    }

    private static String seedWithScheduledFor(Seed s, Instant scheduledFor) {
        String id = seedWriteRow(s);
        DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.SCHEDULED_FOR, scheduledFor.atOffset(ZoneOffset.UTC))
                .where(MSG_DISPATCH_JOBS.ID.eq(id)).execute();
        return id;
    }

    // ── (a) every field of spec §2, and the wire round trip ────────────────

    @Test
    void publishedMessageCarriesEveryFieldAndVerifiesWithTheSchedulerSecret() {
        String clientId = SchedulerFixture.client("acmeco" + RUN);
        // Lowercase, `dp...`-shaped: `msg_dispatch_pools` is a table
        // DispatchPoolApiTest's unscoped list endpoint also sorts and reads;
        // an uppercase-leading code sorts differently under Postgres's
        // default collation than under Java's `String.compareTo`.
        String poolId = SchedulerFixture.pool("dpsched-fast-" + RUN, clientId, "acmeco" + RUN);
        String group = "grp-full-" + RUN;
        String jobId = seedWriteRow(Seed.of(code("full")).withDispatchPoolId(poolId).withClientId(clientId)
                .withMessageGroup(group).withMode("BLOCK_ON_ERROR"));

        poller(new PostgresQueuePublisher(DATA_SOURCE, SETTINGS), () -> true).pollOnce();

        assertThat(REPO.findById(jobId).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);

        Record row = queueRow(jobId);
        assertThat(row).as("published row exists in queue_messages").isNotNull();
        assertThat(row.get("message_group_id", String.class)).isEqualTo(group);
        assertThat(row.get("receive_count", Integer.class)).isZero();
        assertThat(row.get("receipt_handle")).isNull();

        Message message = Json.read(row.get("payload", String.class), Message.class);
        assertThat(message.id()).isEqualTo(jobId);
        assertThat(message.poolCode()).isEqualTo("acmeco" + RUN + "-dpsched-fast-" + RUN);
        assertThat(message.mediationType()).isEqualTo(MediationType.HTTP);
        assertThat(message.mediationTarget()).isEqualTo(PROCESSING_ENDPOINT);
        assertThat(message.messageGroupId()).isEqualTo(group);
        assertThat(message.highPriority()).isFalse();
        assertThat(message.signingSecret()).as("never set for a dispatch job").isNull();
        assertThat(message.dispatchMode()).isEqualTo(DispatchMode.BLOCK_ON_ERROR);
        assertThat(AUTH.verify(jobId, message.authToken()))
                .as("authToken verifies against the scheduler's own dispatch-auth secret")
                .isTrue();
    }

    @Test
    void ungroupedJobPublishesWithNoMessageGroupId() {
        String jobId = seedWriteRow(Seed.of(code("ungrouped")));

        poller(new PostgresQueuePublisher(DATA_SOURCE, SETTINGS), () -> true).pollOnce();

        Message message = Json.read(queueRow(jobId).get("payload", String.class), Message.class);
        assertThat(message.messageGroupId()).isNull();
        assertThat(message.dispatchMode()).as("stored IMMEDIATE parses to IMMEDIATE").isEqualTo(DispatchMode.IMMEDIATE);
    }

    // ── (c) claim-time BLOCK_ON_ERROR hold-back, positional ────────────────

    @Test
    void blockOnErrorHoldsBehindAFailedSiblingWhileAheadAndNextOnErrorJobsStillDispatch() {
        String group = "grp-holdback-" + RUN;
        Instant t = Instant.now().minusSeconds(30);
        String ahead = seedWriteRow(Seed.of(code("ahead")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(1).withCreatedAt(t));
        String failedHead = seedWriteRow(Seed.of(code("failedhead")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(2).withCreatedAt(t.plusSeconds(1)).withStatus("FAILED"));
        String heldBlock = seedWriteRow(Seed.of(code("heldblock")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(3).withCreatedAt(t.plusSeconds(2)));
        String flowingNext = seedWriteRow(Seed.of(code("flowingnext")).withMessageGroup(group).withMode("NEXT_ON_ERROR")
                .withSequence(4).withCreatedAt(t.plusSeconds(3)));

        poller(FakeDispatchPublisher.succeeding(), () -> true).pollOnce();

        assertThat(REPO.findById(ahead).orElseThrow().status())
                .as("positioned BEFORE the FAILED head — the check is positional, not set membership")
                .isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(REPO.findById(failedHead).orElseThrow().status()).isEqualTo(DispatchJobStatus.FAILED);
        assertThat(REPO.findById(heldBlock).orElseThrow().status())
                .as("BLOCK_ON_ERROR behind a FAILED sibling stays PENDING")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(flowingNext).orElseThrow().status())
                .as("NEXT_ON_ERROR never holds for a failed sibling")
                .isEqualTo(DispatchJobStatus.QUEUED);
    }

    @Test
    void aBackedOffPendingSiblingHoldsItsGroupExactlyAsAFailedHeadWould() {
        String group = "grp-backoff-holdback-" + RUN;
        Instant t = Instant.now().minusSeconds(30);
        String backedOff = seedWithScheduledFor(
                Seed.of(code("backedoff")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                        .withSequence(1).withCreatedAt(t),
                Instant.now().plusSeconds(120));
        String heldByBackoff = seedWriteRow(Seed.of(code("heldbyback")).withMessageGroup(group).withMode("BLOCK_ON_ERROR")
                .withSequence(2).withCreatedAt(t.plusSeconds(1)));

        poller(FakeDispatchPublisher.succeeding(), () -> true).pollOnce();

        assertThat(REPO.findById(backedOff).orElseThrow().status())
                .as("excluded from the claim query by its own future scheduled_for, not by the hold-back filter")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(heldByBackoff).orElseThrow().status())
                .as("a sibling behind a mid-backoff PENDING row is held the same way a FAILED head holds it")
                .isEqualTo(DispatchJobStatus.PENDING);
    }

    // ── (d) publish failure reverts the whole batch; the QUEUED guard ──────

    @Test
    void publishFailureLeavesTheWholeClaimedBatchPending() {
        String jobA = seedWriteRow(Seed.of(code("reverta")));
        String jobB = seedWriteRow(Seed.of(code("revertb")));

        poller(FakeDispatchPublisher.failing(), () -> true).pollOnce();

        assertThat(REPO.findById(jobA).orElseThrow().status())
                .as("publish failed; nothing in the claimed batch is marked QUEUED")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(jobB).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
    }

    /// Ruling O2: a chunked publisher reports only the jobs it actually
    /// failed to publish, and the poller must revert exactly those — never
    /// the whole claimed batch, and never nothing. Both sides of the counter
    /// are asserted: a version that reverted everything (the old contract)
    /// would fail the QUEUED assertion, and a version that reverted nothing
    /// (a caller that ignored the exception's ids) would fail the PENDING
    /// assertion — an assertion of only one side would pass under either bug.
    @Test
    void partialPublishFailureRevertsOnlyTheUnpublishedJobsLeavingTheRestQueued() {
        String published = seedWriteRow(Seed.of(code("partialok")));
        String unpublished = seedWriteRow(Seed.of(code("partialfail")));

        poller(FakeDispatchPublisher.failingForJobIds(java.util.Set.of(unpublished)), () -> true).pollOnce();

        assertThat(REPO.findById(published).orElseThrow().status())
                .as("published job stays QUEUED — a partial failure must not revert it")
                .isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(REPO.findById(unpublished).orElseThrow().status())
                .as("the one job the publisher reported unpublished reverts to PENDING")
                .isEqualTo(DispatchJobStatus.PENDING);
    }

    // ── (d2) no QUEUED row without a message (review 2026-09-28) ──────────
    //
    // The old order committed QUEUED and then published, so a process death in
    // between stranded the jobs QUEUED for ever (the stale sweep is gone by
    // ruling 2026-09-22). QUEUED is now marked in the claim transaction after
    // the broker has accepted the job.

    @Test
    void whenThePublisherRunsTheJobIsNotYetCommittedQueued() {
        String job = seedWriteRow(Seed.of(code("orderpending")));
        var seenAtPublish = new java.util.concurrent.atomic.AtomicReference<DispatchJobStatus>();
        DispatchPublisher observing = batch -> {
            // Read on another connection: what a restarted process would see
            // if this one died right now.
            seenAtPublish.set(REPO.findById(job).orElseThrow().status());
        };

        poller(observing, () -> true).pollOnce();

        assertThat(seenAtPublish.get())
                .as("mutant: commit QUEUED before publishing — a death here strands the job")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(job).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    @Test
    void aDeathBetweenPublishAndCommitLeavesTheJobPendingAndTheNextTickPublishesItAgain() {
        String job = seedWriteRow(Seed.of(code("crashwindow")));
        var published = new java.util.concurrent.CopyOnWriteArrayList<String>();
        DispatchPublisher publishesThenDies = batch -> {
            batch.forEach(m -> published.add(m.jobId()));
            throw new IllegalStateException("simulated process death after the broker accepted the batch");
        };

        try {
            poller(publishesThenDies, () -> true).pollOnce();
        } catch (IllegalStateException expected) {
            // the death
        }

        assertThat(published).contains(job);
        assertThat(REPO.findById(job).orElseThrow().status())
                .as("mutant: commit QUEUED before publishing — the job is stranded QUEUED")
                .isEqualTo(DispatchJobStatus.PENDING);

        // Recovery is the next ordinary tick, not a sweep.
        var republish = FakeDispatchPublisher.succeeding();
        poller(republish, () -> true).pollOnce();

        assertThat(republish.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId))
                .as("the next tick publishes it again").contains(job);
        assertThat(REPO.findById(job).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    @Test
    void theDuplicateCopyAPublishThenDeathLeavesIsDiscardedByTheDeliveryClaim() {
        // Two copies of the job reached the broker (the dying tick's and the
        // recovering one's). /process owns a delivery only by winning the
        // status-guarded claim, so the second copy never reaches the subscriber.
        String job = seedWriteRow(Seed.of(code("dupcopy")));
        poller(FakeDispatchPublisher.succeeding(), () -> true).pollOnce();
        var row = REPO.findById(job).orElseThrow();

        boolean firstCopy = REPO.claimForDelivery(job, row.createdAt());
        REPO.markCompleted(job, row.createdAt(), Instant.now(), 5L);
        boolean secondCopy = REPO.claimForDelivery(job, row.createdAt());

        assertThat(firstCopy).isTrue();
        assertThat(secondCopy).as("the second copy is acked without a delivery").isFalse();
        assertThat(REPO.findById(job).orElseThrow().status()).isEqualTo(DispatchJobStatus.COMPLETED);
    }

    /// Review 2026-09-28: `claimForDelivery` refuses a job that is not yet
    /// due, so a stale copy cannot retry early. That refusal must not strand
    /// the job: it stays PENDING, and the next poller tick after it falls due
    /// publishes a copy that IS claimable. Mutant: a claim guard stricter than
    /// the claim query's (say, `scheduled_for IS NULL` only) refuses that copy
    /// too, and the job is published for ever and delivered never.
    @Test
    void aJobRefusedAsNotYetDueIsPublishedAndClaimableOnceDue() {
        String job = seedWithScheduledFor(Seed.of(code("notyetdue")), Instant.now().plusSeconds(600));
        var row = REPO.findById(job).orElseThrow();

        assertThat(REPO.claimForDelivery(job, row.createdAt())).as("a stale copy before it is due").isFalse();
        assertThat(REPO.findById(job).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        var early = FakeDispatchPublisher.succeeding();
        poller(early, () -> true).pollOnce();
        assertThat(early.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId))
                .as("not published before it is due either").doesNotContain(job);

        DB.update(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.SCHEDULED_FOR, Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC))
                .where(MSG_DISPATCH_JOBS.ID.eq(job)).execute();
        var due = FakeDispatchPublisher.succeeding();
        poller(due, () -> true).pollOnce();

        assertThat(due.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).contains(job);
        assertThat(REPO.claimForDelivery(job, row.createdAt())).as("the copy published once due is delivered")
                .isTrue();
    }

    // ── (e) paused connection ───────────────────────────────────────────────

    @Test
    void aPausedConnectionsJobsAreSkippedWhileAnActiveOnesStillFlow() {
        String pausedConn = SchedulerFixture.connection("PAUSED");
        String pausedSub = SchedulerFixture.subscription(pausedConn);
        String activeConn = SchedulerFixture.connection("ACTIVE");
        String activeSub = SchedulerFixture.subscription(activeConn);

        String heldJob = seedWriteRow(Seed.of(code("pausedjob")).withSubscriptionId(pausedSub));
        String flowingJob = seedWriteRow(Seed.of(code("activejob")).withSubscriptionId(activeSub));

        poller(FakeDispatchPublisher.succeeding(), () -> true).pollOnce();

        assertThat(REPO.findById(heldJob).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(flowingJob).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    // ── (g) not leader claims nothing ───────────────────────────────────────

    @Test
    void notLeaderClaimsNothing() {
        String jobId = seedWriteRow(Seed.of(code("notleader")));

        poller(FakeDispatchPublisher.succeeding(), () -> false).pollOnce();

        assertThat(REPO.findById(jobId).orElseThrow().status())
                .as("a non-leader tick must not claim — the status counter must not change")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(queueRow(jobId)).as("nothing was published either").isNull();
    }

    // ── (h) claim order is total, even on a tie ─────────────────────────────

    @Test
    void claimOrderIsTotalEvenWhenSequenceAndCreatedAtTie() throws Exception {
        Instant t = Instant.now().minusSeconds(5);
        String group = "grp-order-" + RUN;
        String idLow = "a" + RUN + "order1";
        String idHigh = "z" + RUN + "order2";
        seedWriteRow(new Seed(idHigh, code("orderhigh"), null, "PENDING", t, null, null, null, group,
                0, null, null, null, null, null, null, null, "IMMEDIATE", 7, t, "EVENT", "exponential", null));
        seedWriteRow(new Seed(idLow, code("orderlow"), null, "PENDING", t, null, null, null, group,
                0, null, null, null, null, null, null, null, "IMMEDIATE", 7, t, "EVENT", "exponential", null));

        try (Connection conn = DATA_SOURCE.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<DispatchJobRepository.ClaimRow> claims = REPO.claimPending(DbTx.wrapForBootstrap(conn), 5000);
                List<String> myOrder = claims.stream().map(DispatchJobRepository.ClaimRow::id)
                        .filter(id -> id.equals(idLow) || id.equals(idHigh)).toList();
                assertThat(myOrder).as("equal sequence and created_at; the id breaks the tie ascending")
                        .containsExactly(idLow, idHigh);
            } finally {
                conn.rollback();
            }
        }
    }
}

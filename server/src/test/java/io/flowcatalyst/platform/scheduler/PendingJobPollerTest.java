package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [PendingJobPoller] and its lanes against a real embedded Postgres
/// (dispatch-seam spec §2, §3): every field of the published message and its
/// round trip through the router's own [Message] parser, the claim-time
/// `BLOCK_ON_ERROR` hold-back's positional semantics, publish failure leaving
/// the batch `PENDING`, the publish-before-mark order that leaves no row
/// `QUEUED` without a message, the status-guarded `QUEUED` update, the
/// paused-connection filter, leader-gating, and the claim query's total
/// order. A claim hands its rows to lanes and returns, so each test polls
/// once and then waits for the lanes to go idle ([#pollAndSettle]); a large
/// test-only batch size keeps these robust against the `PENDING` rows other
/// tests in this class leave behind (the database is per class, rows are
/// not truncated between its tests).
class PendingJobPollerTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);
    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DATA_SOURCE);
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

    private static final SchedulerConfig CONFIG = SchedulerConfig.DEFAULTS.withBufferCapacity(5000).withBatchSize(5000);

    private final List<PendingJobPoller> pollers = new ArrayList<>();

    private PendingJobPoller poller(DispatchPublisher publisher, BooleanSupplier leader) {
        var poller = new PendingJobPoller(DATA_SOURCE, REPO, LIFECYCLE, new PausedConnectionCache(DATA_SOURCE),
                new PoolCodeResolver(DATA_SOURCE), publisher, AUTH, PROCESSING_ENDPOINT, leader, CONFIG);
        pollers.add(poller);
        return poller;
    }

    @AfterEach
    void closePollers() {
        pollers.forEach(PendingJobPoller::close);
        pollers.clear();
    }

    /// One claim, then wait (bounded) for the lanes to publish, mark and go idle.
    private static PendingJobPoller.PollResult pollAndSettle(PendingJobPoller poller) {
        var result = poller.pollOnce();
        assertThat(poller.awaitIdle(Duration.ofSeconds(15))).as("the lanes went idle").isTrue();
        return result;
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

        pollAndSettle(poller(new PostgresQueuePublisher(DATA_SOURCE, SETTINGS), () -> true));

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

        pollAndSettle(poller(new PostgresQueuePublisher(DATA_SOURCE, SETTINGS), () -> true));

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

        pollAndSettle(poller(FakeDispatchPublisher.succeeding(), () -> true));

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

        pollAndSettle(poller(FakeDispatchPublisher.succeeding(), () -> true));

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

        pollAndSettle(poller(FakeDispatchPublisher.failing(), () -> true));

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

        pollAndSettle(poller(FakeDispatchPublisher.failingForJobIds(java.util.Set.of(unpublished)), () -> true));

        assertThat(REPO.findById(published).orElseThrow().status())
                .as("published job stays QUEUED — a partial failure must not revert it")
                .isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(REPO.findById(unpublished).orElseThrow().status())
                .as("the one job the publisher reported unpublished reverts to PENDING")
                .isEqualTo(DispatchJobStatus.PENDING);
    }

    // ── (d2) no QUEUED row without a message (review 2026-09-28) ──────────
    //
    // The old order marked QUEUED and then published, so a process death in
    // between stranded the jobs QUEUED for ever (the stale sweep is gone by
    // ruling 2026-09-22). QUEUED is now written by the lane only after the
    // broker has accepted the job — and with no transaction around the claim,
    // nothing else holds the row meanwhile.

    @Test
    void whenThePublisherRunsTheJobIsNotYetQueued() {
        String job = seedWriteRow(Seed.of(code("orderpending")));
        var seenAtPublish = new java.util.concurrent.atomic.AtomicReference<DispatchJobStatus>();
        DispatchPublisher observing = batch -> {
            // Read on another connection: what a restarted process would see
            // if this one died right now.
            seenAtPublish.set(REPO.findById(job).orElseThrow().status());
        };

        pollAndSettle(poller(observing, () -> true));

        assertThat(seenAtPublish.get())
                .as("mutant: mark QUEUED before publishing — a death here strands the job")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(job).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    @Test
    void aDeathBetweenPublishAndMarkLeavesTheJobPendingAndTheNextClaimPublishesItAgain() {
        String job = seedWriteRow(Seed.of(code("crashwindow")));
        var published = new java.util.concurrent.CopyOnWriteArrayList<String>();
        DispatchPublisher publishesThenDies = batch -> {
            batch.forEach(m -> published.add(m.jobId()));
            throw new IllegalStateException("simulated failure after the broker accepted the batch");
        };

        pollAndSettle(poller(publishesThenDies, () -> true));

        assertThat(published).contains(job);
        assertThat(REPO.findById(job).orElseThrow().status())
                .as("mutant: mark QUEUED before publishing — the job is stranded QUEUED")
                .isEqualTo(DispatchJobStatus.PENDING);

        // Recovery is the next ordinary claim, not a sweep.
        var republish = FakeDispatchPublisher.succeeding();
        pollAndSettle(poller(republish, () -> true));

        assertThat(republish.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId))
                .as("the next claim publishes it again").contains(job);
        assertThat(REPO.findById(job).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    /// The claim holds no lock, so the router can deliver a copy while the
    /// row is still `PENDING`, and `/api/dispatch/process` accepts that
    /// (`claimForDelivery` takes `PENDING`/`QUEUED`). The lane's `QUEUED`
    /// update then runs against a job that has moved on: it must change nothing
    /// and count itself. Mutant: drop `AND status = 'PENDING'` from the update —
    /// the job is dragged back to `QUEUED` after its delivery began.
    @Test
    void aDeliveryThatBeatsTheQueuedUpdateIsNotRegressedToQueued() {
        String job = seedWriteRow(Seed.of(code("callbackfirst")));
        var createdAt = REPO.findById(job).orElseThrow().createdAt();
        var claimedByCallback = new java.util.concurrent.atomic.AtomicBoolean();
        DispatchPublisher callbackArrivesBeforeTheUpdate = batch -> {
            if (batch.stream().anyMatch(m -> m.jobId().equals(job))) {
                claimedByCallback.set(LIFECYCLE.claimForDelivery(job, createdAt));
            }
        };
        var poller = poller(callbackArrivesBeforeTheUpdate, () -> true);

        pollAndSettle(poller);

        assertThat(claimedByCallback).as("the callback won the delivery claim while the row was PENDING").isTrue();
        assertThat(REPO.findById(job).orElseThrow().status())
                .as("mutant: unguarded QUEUED update regresses PROCESSING")
                .isEqualTo(DispatchJobStatus.PROCESSING);
        assertThat(poller.metrics().markNotUpdated()).isGreaterThanOrEqualTo(1);
    }

    /// The race a status guard alone cannot see: the callback processes the
    /// job and reschedules it back to `PENDING` (a retry) before the lane's
    /// update runs. The row is `PENDING` again, but its message is gone, so it
    /// must not be marked `QUEUED`. Mutant: guard on the status only.
    @Test
    void aJobTheCallbackRescheduledBackToPendingBeforeTheQueuedUpdateIsNotMarkedQueued() {
        String job = seedWriteRow(Seed.of(code("callbackretry")));
        var createdAt = REPO.findById(job).orElseThrow().createdAt();
        DispatchPublisher callbackRunsAndRetries = batch -> {
            if (batch.stream().anyMatch(m -> m.jobId().equals(job))) {
                assertThat(LIFECYCLE.claimForDelivery(job, createdAt)).isTrue();
                LIFECYCLE.scheduleRetry(job, createdAt, Instant.now().minusSeconds(1), 1, "subscriber 500");
            }
        };
        var poller = poller(callbackRunsAndRetries, () -> true);

        pollAndSettle(poller);

        assertThat(REPO.findById(job).orElseThrow().status())
                .as("mutant: status-only guard marks a PENDING row whose message is gone")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(poller.metrics().markNotUpdated()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theDuplicateCopyAPublishThenDeathLeavesIsDiscardedByTheDeliveryClaim() {
        // Two copies of the job reached the broker (the dying tick's and the
        // recovering one's). /process owns a delivery only by winning the
        // status-guarded claim, so the second copy never reaches the subscriber.
        String job = seedWriteRow(Seed.of(code("dupcopy")));
        pollAndSettle(poller(FakeDispatchPublisher.succeeding(), () -> true));
        var row = REPO.findById(job).orElseThrow();

        boolean firstCopy = LIFECYCLE.claimForDelivery(job, row.createdAt());
        LIFECYCLE.markCompleted(job, row.createdAt(), Instant.now(), 5L);
        boolean secondCopy = LIFECYCLE.claimForDelivery(job, row.createdAt());

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

        assertThat(LIFECYCLE.claimForDelivery(job, row.createdAt())).as("a stale copy before it is due").isFalse();
        assertThat(REPO.findById(job).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        var early = FakeDispatchPublisher.succeeding();
        pollAndSettle(poller(early, () -> true));
        assertThat(early.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId))
                .as("not published before it is due either").doesNotContain(job);

        DB.update(MSG_DISPATCH_JOBS)
                .set(MSG_DISPATCH_JOBS.SCHEDULED_FOR, Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC))
                .where(MSG_DISPATCH_JOBS.ID.eq(job)).execute();
        var due = FakeDispatchPublisher.succeeding();
        pollAndSettle(poller(due, () -> true));

        assertThat(due.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).contains(job);
        assertThat(LIFECYCLE.claimForDelivery(job, row.createdAt())).as("the copy published once due is delivered")
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

        pollAndSettle(poller(FakeDispatchPublisher.succeeding(), () -> true));

        assertThat(REPO.findById(heldJob).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(REPO.findById(flowingJob).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    // ── (g) not leader claims nothing ───────────────────────────────────────

    @Test
    void notLeaderClaimsNothing() {
        String jobId = seedWriteRow(Seed.of(code("notleader")));

        pollAndSettle(poller(FakeDispatchPublisher.succeeding(), () -> false));

        assertThat(REPO.findById(jobId).orElseThrow().status())
                .as("a non-leader tick must not claim — the status counter must not change")
                .isEqualTo(DispatchJobStatus.PENDING);
        assertThat(queueRow(jobId)).as("nothing was published either").isNull();
    }

    // ── (h) claim order is total, even on a tie ─────────────────────────────

    @Test
    void claimOrderIsTotalEvenWhenSequenceAndCreatedAtTie() {
        Instant t = Instant.now().minusSeconds(5);
        String group = "grp-order-" + RUN;
        String idLow = "a" + RUN + "order1";
        String idHigh = "z" + RUN + "order2";
        seedWriteRow(new Seed(idHigh, code("orderhigh"), null, "PENDING", t, null, null, null, group,
                0, null, null, null, null, null, null, null, "IMMEDIATE", 7, t, "EVENT", "exponential", null));
        seedWriteRow(new Seed(idLow, code("orderlow"), null, "PENDING", t, null, null, null, group,
                0, null, null, null, null, null, null, null, "IMMEDIATE", 7, t, "EVENT", "exponential", null));

        List<DispatchJobRepository.ClaimRow> claims = REPO.claimPending(5000, java.util.Set.of(), java.util.Set.of(), List.of());
        List<String> myOrder = claims.stream().map(DispatchJobRepository.ClaimRow::id)
                .filter(id -> id.equals(idLow) || id.equals(idHigh)).toList();
        assertThat(myOrder).as("equal sequence and created_at; the id breaks the tie ascending")
                .containsExactly(idLow, idHigh);
    }
}

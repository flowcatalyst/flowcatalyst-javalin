package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle.QueueDrift;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.sdk.tsid.Tsid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.assertQueueMirrorsJob;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.queueRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRowOnly;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.setStatusWhere;
import static org.assertj.core.api.Assertions.assertThat;

/// The scheduler's claim (two plain statements, delete at claim), the restore of a claimed job, the
/// reconcile sweep (also the crash recovery), the stale-`QUEUED` recovery and the backlog (dispatch-queue
/// spec step 3b), against a real database. Every test starts from an empty queue.
class DispatchQueueClaimTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DS);
    private static final DispatchJobRepository REPO = new DispatchJobRepository(DS);
    private static final Set<String> NONE = Set.of();

    @BeforeEach
    void emptyQueue() {
        setStatusWhere("COMPLETED", "PENDING", "QUEUED", "PROCESSING");
        assertThat(DB.fetchOne("SELECT count(*) FROM msg_dispatch_queue").get(0, Long.class)).isZero();
    }

    private static Seed seed(String tag) {
        return Seed.of(code(tag)).withMode("BLOCK_ON_ERROR");
    }

    private static List<String> ids(List<ClaimRow> rows) {
        return rows.stream().map(ClaimRow::id).toList();
    }

    private static List<ClaimRow> claim(int limit) {
        return LIFECYCLE.claimPending(limit, NONE, NONE);
    }

    private static Instant at(int seconds) {
        return Instant.now().minusSeconds(100).plusSeconds(seconds);
    }

    // ── the claim ──────────────────────────────────────────────────────────

    /// Group (NULL last), sequence, creation time, id — whatever order the rows went in.
    @Test
    void theClaimReturnsRowsInGroupSequenceCreatedAtIdOrderWithUngroupedLast() {
        String g1 = "ord-a-" + RUN;
        String g2 = "ord-b-" + RUN;
        Instant t = at(0);
        String ungrouped = seedWriteRow(seed("o1").withCreatedAt(t));
        String b2 = seedWriteRow(seed("o2").withMessageGroup(g2).withSequence(1).withCreatedAt(t));
        String a2late = seedWriteRow(seed("o3").withMessageGroup(g1).withSequence(2).withCreatedAt(t.plusSeconds(5)));
        String a2early = seedWriteRow(seed("o4").withMessageGroup(g1).withSequence(2).withCreatedAt(t));
        String a1 = seedWriteRow(seed("o5").withMessageGroup(g1).withSequence(1).withCreatedAt(t.plusSeconds(9)));
        String idLow = "a" + RUN + "tie1";
        String idHigh = "z" + RUN + "tie2";
        seedWriteRow(withId(seed("o6").withMessageGroup(g1).withSequence(3).withCreatedAt(t), idHigh));
        seedWriteRow(withId(seed("o7").withMessageGroup(g1).withSequence(3).withCreatedAt(t), idLow));

        assertThat(ids(claim(100))).containsExactly(a1, a2early, a2late, idLow, idHigh, b2, ungrouped);
    }

    private static Seed withId(Seed s, String id) {
        return new Seed(id, s.code(), s.clientId(), s.status(), s.createdAt(), s.eventId(), s.subscriptionId(),
                s.dispatchPoolId(), s.messageGroup(), s.attemptCount(), s.scheduledFor(), s.completedAt(),
                s.durationMillis(), s.lastError(), s.payload(), s.metadataJson(), s.source(), s.mode(), s.sequence(),
                s.updatedAt(), s.kind(), s.retryStrategy(), s.descriptor());
    }

    @Test
    void theClaimCarriesTheQueueRowsValuesAndTheVersionTheMarkQueuedGuardsOn() {
        String id = seedWriteRow(seed("vals").withMessageGroup("vals-" + RUN).withSequence(4).withClientId("cli" + RUN.substring(0, 5))
                .withDispatchPoolId("dpl" + RUN).withSubscriptionId("sub" + RUN).withCreatedAt(at(0)));
        var job = REPO.findById(id).orElseThrow();

        ClaimRow row = claim(10).getFirst();

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.messageGroup()).isEqualTo("vals-" + RUN);
        assertThat(row.sequence()).isEqualTo(4);
        assertThat(row.mode()).isEqualTo(io.flowcatalyst.platform.shared.dispatch.DispatchMode.BLOCK_ON_ERROR);
        assertThat(row.dispatchPoolId()).isEqualTo("dpl" + RUN);
        assertThat(row.subscriptionId()).isEqualTo("sub" + RUN);
        assertThat(row.createdAt()).isEqualTo(job.createdAt());
        assertThat(row.updatedAt()).as("the version is the job's updated_at").isEqualTo(job.updatedAt());
        assertThat(LIFECYCLE.markQueued(List.of(row))).isEqualTo(1);
    }

    /// The claim DELETES: the row is gone, so it cannot be claimed again until it is restored.
    /// Mutant: S2 does not delete (claim S1's ids regardless) — the row comes back.
    @Test
    void aClaimedRowIsGoneFromTheQueueUntilItIsRestored() {
        String id = seedWriteRow(seed("again").withMessageGroup("again-" + RUN));

        var claimed = claim(10);
        assertThat(ids(claimed)).containsExactly(id);
        assertThat(queueRow(id)).as("deleted at claim").isNull();
        assertThat(REPO.findById(id).orElseThrow().status()).as("still PENDING: in flight").isEqualTo(DispatchJobStatus.PENDING);
        assertThat(claim(10)).as("claimed: not claimable").isEmpty();

        assertThat(LIFECYCLE.restore(claimed)).isEqualTo(1);
        assertQueueMirrorsJob(id, "restored");
        assertThat(ids(claim(10))).as("restored: claimed again").containsExactly(id);
    }

    /// A job that re-enters PENDING while in flight (a deferral) gets its row from the lifecycle itself.
    @Test
    void aJobThatReEntersPendingWhileInFlightIsClaimableAgain() {
        String id = seedWriteRow(seed("reenter").withMessageGroup("reenter-" + RUN).withCreatedAt(at(0)));
        assertThat(claim(10)).hasSize(1);
        Instant created = REPO.findById(id).orElseThrow().createdAt();

        LIFECYCLE.reschedule(id, created, Instant.now().minusSeconds(1));

        assertQueueMirrorsJob(id, "re-entered");
        assertThat(ids(claim(10))).containsExactly(id);
    }

    @Test
    void theClaimSkipsNotYetDuePausedAndHeldGroupRowsAndNothingElse() {
        String g = "skip-" + RUN;
        String heldGroup = "held-" + RUN;
        String due = seedWriteRow(seed("due").withMessageGroup(g).withSequence(1).withScheduledFor(Instant.now().minusSeconds(30)));
        String future = seedWriteRow(seed("future").withMessageGroup(g).withSequence(2).withScheduledFor(Instant.now().plusSeconds(600)));
        String paused = seedWriteRow(seed("paused").withMessageGroup(g).withSequence(3).withSubscriptionId("pausedsub" + RUN));
        String other = seedWriteRow(seed("other").withMessageGroup(g).withSequence(4).withSubscriptionId("othersub" + RUN));
        String noSub = seedWriteRow(seed("nosub").withMessageGroup(g).withSequence(5));
        String held = seedWriteRow(seed("held").withMessageGroup(heldGroup).withSequence(1));
        String ungrouped = seedWriteRow(seed("ung"));

        var claimed = ids(LIFECYCLE.claimPending(100, Set.of("pausedsub" + RUN), Set.of(heldGroup)));

        assertThat(claimed).as("not-yet-due, paused and held-group rows are skipped; no subscription / no group never is")
                .containsExactlyInAnyOrder(due, other, noSub, ungrouped);
        assertThat(queueRow(paused)).as("a skipped row is untouched, not claimed").isNotNull();
        assertThat(queueRow(future)).isNotNull();
        assertThat(queueRow(held)).isNotNull();
        // empty arrays bind as empty, not NULL: nothing is excluded
        assertThat(ids(claim(100))).containsExactlyInAnyOrder(paused, held);
    }

    @Test
    void theClaimHonoursItsLimitAndTakesTheFirstRowsInOrder() {
        String g = "limit-" + RUN;
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 6; i++) mine.add(seedWriteRow(seed("lim" + i).withMessageGroup(g).withSequence(i)));

        assertThat(ids(claim(2))).containsExactly(mine.get(0), mine.get(1));
        assertThat(ids(claim(3))).containsExactly(mine.get(2), mine.get(3), mine.get(4));
        assertThat(ids(claim(100))).containsExactly(mine.get(5));
    }

    /// A row made not-due between S1 (which chose it) and S2 (which deletes it) is not claimed.
    /// Mutant: S2 without the due condition.
    @Test
    void aRowMadeNotDueBetweenTheTwoStatementsIsNotClaimed() throws Exception {
        String g = "s2-" + RUN;
        String stays = seedWriteRow(seed("s2a").withMessageGroup(g).withSequence(1));
        String goes = seedWriteRow(seed("s2b").withMessageGroup(g).withSequence(2));
        // S1 chose both; a retry then pushes `stays` into the future
        DB.execute("UPDATE msg_dispatch_queue SET scheduled_for = now() + interval '10 minutes' WHERE job_id = ?", stays);

        try (var conn = DS.getConnection()) {
            var taken = DispatchJobLifecycle.takeRows(conn, List.of(stays, goes));
            assertThat(ids(taken)).containsExactly(goes);
        }
        assertThat(queueRow(stays)).as("S2 left the refreshed row in the queue").isNotNull();
        assertThat(queueRow(goes)).isNull();
    }

    /// Several threads claim at once: no row is returned twice, and between them they get every row.
    /// Mutant: S2 not taking RETURNING as the claim (every claimer takes S1's ids).
    @Test
    void concurrentClaimersGetDisjointRowsAndTogetherAllOfThem() throws Exception {
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 300; i++) mine.add(seedWriteRow(seed("cc" + i).withMessageGroup("cc-" + RUN + "-" + (i % 7)).withSequence(i)));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int w = 0; w < 6; w++) {
                futures.add(pool.submit(() -> {
                    List<String> got = new ArrayList<>();
                    for (int round = 0; round < 40; round++) got.addAll(ids(claim(11)));
                    return got;
                }));
            }
            List<String> all = new ArrayList<>();
            for (var f : futures) all.addAll(f.get(60, TimeUnit.SECONDS));

            assertThat(all).as("no row returned to two claims").doesNotHaveDuplicates();
            assertThat(all).as("and every row is returned to one").containsExactlyInAnyOrderElementsOf(mine);
        } finally {
            pool.shutdownNow();
        }
    }

    /// The claim never reads `msg_dispatch_jobs`: a queue row whose job has gone is still claimed.
    @Test
    void theClaimReadsOnlyTheQueueTable() {
        String ghost = Tsid.generate();
        DB.execute("INSERT INTO msg_dispatch_queue (job_id, job_created_at, sequence, mode, version)"
                + " VALUES (?, now(), 1, 'IMMEDIATE', now())", ghost);
        assertThat(ids(claim(10))).containsExactly(ghost);
    }

    @Test
    void markQueuedAfterTheClaimMakesTheJobQueuedWithNoQueueRowToRestore() {
        String id = seedWriteRow(seed("mq").withMessageGroup("mq-" + RUN));
        var row = claim(10).getFirst();
        assertThat(LIFECYCLE.markQueued(List.of(row))).isEqualTo(1);
        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(LIFECYCLE.restore(List.of(row))).as("a QUEUED job is not resurrected").isZero();
        assertThat(queueRow(id)).isNull();
    }

    // ── restore ────────────────────────────────────────────────────────────

    @Test
    void restoreDoesNothingForAJobThatIsNoLongerPending() {
        String id = seedWriteRow(seed("rs1").withMessageGroup("rs-" + RUN));
        var claimed = claim(10);
        setStatusWhere("COMPLETED", "PENDING"); // the job moved on (delivered by a copy, say)

        assertThat(LIFECYCLE.restore(claimed)).isZero();
        assertThat(queueRow(id)).isNull();
    }

    /// A newer queue row (the job re-entered PENDING while in flight) is not overwritten by the restore of the old claim.
    @Test
    void restoreDoesNotOverwriteANewerQueueRow() {
        String id = seedWriteRow(seed("rs2").withMessageGroup("rs2-" + RUN));
        var claimed = claim(10);
        Instant created = REPO.findById(id).orElseThrow().createdAt();
        LIFECYCLE.reschedule(id, created, Instant.now().minusSeconds(1));
        Map<String, Object> newer = queueRow(id);

        assertThat(LIFECYCLE.restore(claimed)).isZero();
        assertThat(queueRow(id)).isEqualTo(newer);
    }

    @Test
    void restoreIsBulkAndTakesTheJobsCurrentValuesFromTheJobTable() {
        String g = "rs3-" + RUN;
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) mine.add(seedWriteRow(seed("rs3" + i).withMessageGroup(g).withSequence(i)));
        var claimed = claim(10);

        assertThat(LIFECYCLE.restore(claimed)).isEqualTo(5);
        for (String id : mine) assertQueueMirrorsJob(id, "restored");
        assertThat(ids(claim(10))).as("claimed again in order").containsExactlyElementsOf(mine);
        assertThat(LIFECYCLE.restore(List.of())).isZero();
    }

    // ── reconcile and crash recovery ───────────────────────────────────────

    private static final Duration THIRTY = Duration.ofSeconds(30);

    @Test
    void aConsistentTableReportsZeroAndChangesNothing() {
        String g = "rc-" + RUN;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(seedWriteRow(seed("rc" + i).withMessageGroup(g).withSequence(i)));
        List<Map<String, Object>> before = ids.stream().map(DispatchJobFixture::queueRow).toList();

        var result = LIFECYCLE.reconcileQueue(5000, Duration.ZERO, NONE);

        assertThat(result.isClean()).as("%s", result).isTrue();
        assertThat(ids.stream().map(DispatchJobFixture::queueRow).toList()).isEqualTo(before);
        assertThat(LIFECYCLE.queueDrift()).isEqualTo(new QueueDrift(0, 0));
    }

    /// (a) A PENDING job with no queue row gets one — but not a job still being written, and not one this
    /// process holds in flight. Mutant: remove reconcile (a).
    @Test
    void reconcileRestoresTheMissingQueueRowOfAPendingJobAndLeavesAYoungOneAlone() {
        String missing = seedWriteRowOnly(seed("miss").withMessageGroup("miss-" + RUN).withUpdatedAt(Instant.now().minusSeconds(3600)));
        String young = seedWriteRowOnly(seed("young").withMessageGroup("miss-" + RUN).withSequence(2));
        assertThat(LIFECYCLE.queueDrift(List.of(missing, young)).missingOrStale()).isEqualTo(2);

        var result = LIFECYCLE.reconcileQueue(5000, THIRTY, NONE);

        assertThat(result.inserted()).as("only the old one").isEqualTo(1);
        assertQueueMirrorsJob(missing, "reconciled");
        assertThat(queueRow(young)).as("younger than the age guard: left alone").isNull();
        assertThat(ids(claim(10))).as("and the restored row is claimable").containsExactly(missing);
    }

    /// The periodic pass excludes this process's in-flight ids: they are PENDING with no row because they are
    /// being published. Mutant: no exclusion — the in-flight job is queued again and published twice.
    @Test
    void theReconcilePassSkipsTheIdsThisProcessHasInFlight() {
        String inFlight = seedWriteRowOnly(seed("if1").withMessageGroup("if-" + RUN).withUpdatedAt(Instant.now().minusSeconds(3600)));
        String crashed = seedWriteRowOnly(seed("if2").withMessageGroup("if-" + RUN).withSequence(2).withUpdatedAt(Instant.now().minusSeconds(3600)));

        var result = LIFECYCLE.reconcileQueue(5000, THIRTY, List.of(inFlight));

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(queueRow(inFlight)).as("being published: not re-queued").isNull();
        assertThat(queueRow(crashed)).as("a dead claimer's leftover: restored").isNotNull();
        assertThat(LIFECYCLE.queueDrift(null, List.of(inFlight))).as("drift ignoring the in-flight id").isEqualTo(new QueueDrift(0, 0));
    }

    /// The leader's start-up pass: no age guard (a job claimed a moment before the crash is restored at once).
    @Test
    void restoreMissingWithNoAgeGuardRestoresAJustClaimedJob() {
        String id = seedWriteRow(seed("start").withMessageGroup("start-" + RUN));
        claim(10); // the dead process claimed it a moment ago
        assertThat(queueRow(id)).isNull();

        assertThat(LIFECYCLE.restoreMissing(5000, Duration.ZERO, NONE)).isEqualTo(1);
        assertQueueMirrorsJob(id, "restored at leader start");
        assertThat(LIFECYCLE.restoreMissing(5000, Duration.ZERO, NONE)).as("a second pass inserts nothing").isZero();
    }

    /// (b) A queue row whose job is not PENDING (or missing) goes.
    @Test
    void reconcileDeletesQueueRowsOfJobsThatAreNotPending() {
        String g = "del-" + RUN;
        String completed = seedWriteRow(seed("d1").withMessageGroup(g).withSequence(1));
        String live = seedWriteRow(seed("d4").withMessageGroup(g).withSequence(4));
        String ghost = Tsid.generate();
        DB.execute("INSERT INTO msg_dispatch_queue (job_id, job_created_at, sequence, mode, version) VALUES (?, now(), 1, 'IMMEDIATE', now())", ghost);
        DB.execute("UPDATE msg_dispatch_jobs SET status = 'COMPLETED' WHERE id = ?", completed); // behind the lifecycle's back

        var result = LIFECYCLE.reconcileQueue(5000, THIRTY, NONE);

        assertThat(result.deleted()).as("the orphan and the ghost").isEqualTo(2);
        assertThat(queueRow(completed)).isNull();
        assertThat(queueRow(ghost)).isNull();
        assertQueueMirrorsJob(live, "untouched");
    }

    /// (c) A queue row whose version differs from its job's updated_at is refreshed from the job.
    @Test
    void reconcileRefreshesAStaleQueueRowFromItsJob() {
        String g = "ref-" + RUN;
        String stale = seedWriteRow(seed("r1").withMessageGroup(g).withSequence(1));
        String fine = seedWriteRow(seed("r2").withMessageGroup(g).withSequence(2));
        DB.execute("UPDATE msg_dispatch_jobs SET message_group = ?, sequence = 9, updated_at = now() WHERE id = ?", g + "-moved", stale);
        Map<String, Object> fineBefore = queueRow(fine);

        var result = LIFECYCLE.reconcileQueue(5000, THIRTY, NONE);

        assertThat(result.refreshed()).isEqualTo(1);
        assertQueueMirrorsJob(stale, "refreshed from its job");
        assertThat(queueRow(fine)).isEqualTo(fineBefore);
    }

    @Test
    void reconcileIsBoundedByItsRowLimitPerStatement() {
        for (int i = 0; i < 7; i++) {
            seedWriteRowOnly(seed("bound" + i).withMessageGroup("bound-" + RUN).withSequence(i).withUpdatedAt(Instant.now().minusSeconds(3600)));
        }
        assertThat(LIFECYCLE.reconcileQueue(3, THIRTY, NONE).inserted()).isEqualTo(3);
        assertThat(LIFECYCLE.reconcileQueue(3, THIRTY, NONE).inserted()).isEqualTo(3);
        assertThat(LIFECYCLE.reconcileQueue(3, THIRTY, NONE).inserted()).isEqualTo(1);
        assertThat(LIFECYCLE.queueDrift()).isEqualTo(new QueueDrift(0, 0));
    }

    // ── stale QUEUED ───────────────────────────────────────────────────────

    @Test
    void recoverStaleQueuedReturnsOnlyJobsOlderThanTheCutoffToPendingWithAQueueRow() {
        String g = "sq-" + RUN;
        String staleQueued = seedWriteRowOnly(seed("q1").withMessageGroup(g).withStatus("QUEUED").withUpdatedAt(Instant.now().minusSeconds(3600)));
        String freshQueued = seedWriteRowOnly(seed("q2").withMessageGroup(g).withStatus("QUEUED").withUpdatedAt(Instant.now().minusSeconds(60)));
        String processing = seedWriteRowOnly(seed("q3").withMessageGroup(g).withStatus("PROCESSING").withUpdatedAt(Instant.now().minusSeconds(3600)));

        int recovered = LIFECYCLE.recoverStaleQueued(Instant.now().minus(Duration.ofMinutes(15)));

        assertThat(recovered).isEqualTo(1);
        assertThat(REPO.findById(staleQueued).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertQueueMirrorsJob(staleQueued, "recovered");
        assertThat(REPO.findById(freshQueued).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(REPO.findById(processing).orElseThrow().status()).as("PROCESSING is the reaper's").isEqualTo(DispatchJobStatus.PROCESSING);
        assertThat(ids(claim(10))).containsExactly(staleQueued);
    }

    // ── backlog ────────────────────────────────────────────────────────────

    @Test
    void theBacklogCountsOnlyDueRowsAndReportsTheOldest() {
        String g = "bl-" + RUN;
        assertThat(LIFECYCLE.queueBacklog().depth()).isZero();
        assertThat(LIFECYCLE.queueBacklog().oldestEnqueuedAt()).isNull();
        String oldest = seedWriteRow(seed("b1").withMessageGroup(g).withSequence(1));
        seedWriteRow(seed("b2").withMessageGroup(g).withSequence(2));
        seedWriteRow(seed("b3").withMessageGroup(g).withSequence(3).withScheduledFor(Instant.now().plusSeconds(600)));
        DB.execute("UPDATE msg_dispatch_queue SET enqueued_at = now() - interval '5 minutes' WHERE job_id = ?", oldest);

        var backlog = LIFECYCLE.queueBacklog();
        assertThat(backlog.depth()).as("not the one that is not yet due").isEqualTo(2);
        assertThat(backlog.oldestEnqueuedAt()).isBefore(Instant.now().minusSeconds(240));

        claim(1);
        assertThat(LIFECYCLE.queueBacklog().depth()).as("a claimed row has left the queue").isEqualTo(1);
    }
}

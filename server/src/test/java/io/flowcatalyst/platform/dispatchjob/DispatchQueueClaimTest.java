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
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.syncQueue;
import static org.assertj.core.api.Assertions.assertThat;

/// The scheduler's claim on `msg_dispatch_queue`, the release of claims, the
/// stale-claim release, the reconcile sweep and the stale-`QUEUED` recovery
/// (dispatch-queue spec step 3, §2, §3, §5, §7), against a real database. Every
/// test starts from an empty queue (the jobs of the previous one are completed).
class DispatchQueueClaimTest {

    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DS);
    private static final DispatchJobRepository REPO = new DispatchJobRepository(DS);

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

    private static Object claimedAt(String id) {
        return queueRow(id).get("claimed_at");
    }

    private static Instant at(int seconds) {
        return Instant.now().minusSeconds(100).plusSeconds(seconds);
    }

    // ── the claim ──────────────────────────────────────────────────────────

    /// Group (NULL last), sequence, creation time, id — whatever order the rows went in.
    /// Mutant: sort the claim's result by another key, or lose the NULLS LAST.
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
        // an exact tie on group, sequence and creation time: the id decides
        Seed tieB = seed("o6").withMessageGroup(g1).withSequence(3).withCreatedAt(t);
        Seed tieA = seed("o7").withMessageGroup(g1).withSequence(3).withCreatedAt(t);
        String idLow = "a" + RUN + "tie1";
        String idHigh = "z" + RUN + "tie2";
        DispatchJobFixture.seedWriteRow(withId(tieB, idHigh));
        DispatchJobFixture.seedWriteRow(withId(tieA, idLow));

        var claimed = LIFECYCLE.claimPending(100, Set.of());

        assertThat(ids(claimed)).containsExactly(a1, a2early, a2late, idLow, idHigh, b2, ungrouped);
    }

    private static Seed withId(Seed s, String id) {
        return new Seed(id, s.code(), s.clientId(), s.status(), s.createdAt(), s.eventId(), s.subscriptionId(),
                s.dispatchPoolId(), s.messageGroup(), s.attemptCount(), s.scheduledFor(), s.completedAt(),
                s.durationMillis(), s.lastError(), s.payload(), s.metadataJson(), s.source(), s.mode(), s.sequence(),
                s.updatedAt(), s.kind(), s.retryStrategy(), s.descriptor());
    }

    /// Mirrors the job's own values: every ClaimRow component comes from the queue row.
    @Test
    void theClaimCarriesTheQueueRowsValuesAndTheVersionTheMarkQueuedGuardsOn() {
        String id = seedWriteRow(seed("vals").withMessageGroup("vals-" + RUN).withSequence(4).withClientId("cli" + RUN.substring(0, 5))
                .withDispatchPoolId("dpl" + RUN).withSubscriptionId("sub" + RUN).withCreatedAt(at(0)));
        var job = REPO.findById(id).orElseThrow();

        ClaimRow row = LIFECYCLE.claimPending(10, Set.of()).getFirst();

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

    /// Mutant: drop `claimed_at IS NULL` — a claimed row comes back.
    @Test
    void aClaimedRowIsNotReturnedAgainUntilItIsReleased() {
        String id = seedWriteRow(seed("again").withMessageGroup("again-" + RUN));

        assertThat(ids(LIFECYCLE.claimPending(10, Set.of()))).containsExactly(id);
        assertThat(claimedAt(id)).isNotNull();
        assertThat(LIFECYCLE.claimPending(10, Set.of())).as("claimed: not claimable").isEmpty();

        assertThat(LIFECYCLE.releaseClaims(List.of(id))).isEqualTo(1);
        assertThat(claimedAt(id)).isNull();
        assertThat(ids(LIFECYCLE.claimPending(10, Set.of()))).as("released: claimed again").containsExactly(id);
    }

    /// Re-entering PENDING (a deferral) resets the claim: the refreshed row is claimable at once.
    @Test
    void aJobThatReEntersPendingIsClaimableAgainWithoutARelease() {
        String id = seedWriteRow(seed("reenter").withMessageGroup("reenter-" + RUN).withCreatedAt(at(0)));
        assertThat(LIFECYCLE.claimPending(10, Set.of())).hasSize(1);
        Instant created = REPO.findById(id).orElseThrow().createdAt();

        LIFECYCLE.reschedule(id, created, Instant.now().minusSeconds(1)); // PENDING again, due

        assertThat(claimedAt(id)).as("entering PENDING resets claimed_at").isNull();
        assertThat(ids(LIFECYCLE.claimPending(10, Set.of()))).containsExactly(id);
    }

    @Test
    void theClaimSkipsNotYetDueAndPausedRowsAndNothingElse() {
        String g = "skip-" + RUN;
        String due = seedWriteRow(seed("due").withMessageGroup(g).withSequence(1).withScheduledFor(Instant.now().minusSeconds(30)));
        String future = seedWriteRow(seed("future").withMessageGroup(g).withSequence(2).withScheduledFor(Instant.now().plusSeconds(600)));
        String paused = seedWriteRow(seed("paused").withMessageGroup(g).withSequence(3).withSubscriptionId("pausedsub" + RUN));
        String other = seedWriteRow(seed("other").withMessageGroup(g).withSequence(4).withSubscriptionId("othersub" + RUN));
        String noSub = seedWriteRow(seed("nosub").withMessageGroup(g).withSequence(5));

        var claimed = ids(LIFECYCLE.claimPending(100, Set.of("pausedsub" + RUN)));

        assertThat(claimed).as("not-yet-due and paused rows are skipped; a row with no subscription never is")
                .containsExactly(due, other, noSub);
        assertThat(claimedAt(paused)).as("a skipped row is untouched, not claimed").isNull();
        assertThat(claimedAt(future)).isNull();
        // an empty paused set binds an empty array, not NULL: nothing is excluded
        assertThat(ids(LIFECYCLE.claimPending(100, Set.of()))).containsExactly(paused);
    }

    @Test
    void theClaimHonoursItsLimitAndTakesTheFirstRowsInOrder() {
        String g = "limit-" + RUN;
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 6; i++) mine.add(seedWriteRow(seed("lim" + i).withMessageGroup(g).withSequence(i)));

        assertThat(ids(LIFECYCLE.claimPending(2, Set.of()))).containsExactly(mine.get(0), mine.get(1));
        assertThat(ids(LIFECYCLE.claimPending(3, Set.of()))).containsExactly(mine.get(2), mine.get(3), mine.get(4));
        assertThat(ids(LIFECYCLE.claimPending(100, Set.of()))).containsExactly(mine.get(5));
    }

    /// Several threads claim at once: no row is returned twice, and between them they
    /// get every row. Mutant: no `FOR UPDATE SKIP LOCKED` and no `claimed_at IS NULL`.
    @Test
    void twoConcurrentClaimsNeverReturnTheSameRow() throws Exception {
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 300; i++) mine.add(seedWriteRow(seed("cc" + i).withMessageGroup("cc-" + RUN + "-" + (i % 7)).withSequence(i)));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int w = 0; w < 6; w++) {
                futures.add(pool.submit(() -> {
                    List<String> got = new ArrayList<>();
                    for (int round = 0; round < 40; round++) got.addAll(ids(LIFECYCLE.claimPending(11, Set.of())));
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
        assertThat(ids(LIFECYCLE.claimPending(10, Set.of()))).containsExactly(ghost);
        DB.execute("DELETE FROM msg_dispatch_queue WHERE job_id = ?", ghost);
    }

    @Test
    void markQueuedAfterTheClaimRemovesTheQueueRow() {
        String id = seedWriteRow(seed("mq").withMessageGroup("mq-" + RUN));
        var row = LIFECYCLE.claimPending(10, Set.of()).getFirst();
        assertThat(LIFECYCLE.markQueued(List.of(row))).isEqualTo(1);
        assertThat(queueRow(id)).isNull();
        assertThat(LIFECYCLE.releaseClaims(List.of(id))).as("nothing left to release").isZero();
    }

    // ── release ────────────────────────────────────────────────────────────

    @Test
    void releaseGivesBackOnlyClaimsAndToleratesUnknownIdsAndRefreshedRows() {
        String g = "rel-" + RUN;
        String claimed = seedWriteRow(seed("rel1").withMessageGroup(g).withSequence(1));
        String unclaimed = seedWriteRow(seed("rel2").withMessageGroup(g).withSequence(2));
        LIFECYCLE.claimPending(1, Set.of());
        assertThat(claimedAt(claimed)).isNotNull();
        assertThat(claimedAt(unclaimed)).isNull();

        assertThat(LIFECYCLE.releaseClaims(List.of())).isZero();
        int released = LIFECYCLE.releaseClaims(List.of(claimed, unclaimed, Tsid.generate()));

        assertThat(released).as("one claim released; an unclaimed row and an unknown id are not matched").isEqualTo(1);
        assertThat(claimedAt(claimed)).isNull();
        assertQueueMirrorsJob(claimed, "released");
    }

    // ── stale claims ───────────────────────────────────────────────────────

    /// What the claimer left when it died: every claim this process does not hold goes back,
    /// the ones it does hold stay. Mutant: release without the in-flight exclusion.
    @Test
    void staleClaimsAreReleasedExceptThoseTheProcessHolds() {
        String g = "stale-" + RUN;
        String mine = seedWriteRow(seed("sc1").withMessageGroup(g).withSequence(1));
        String dead1 = seedWriteRow(seed("sc2").withMessageGroup(g).withSequence(2));
        String dead2 = seedWriteRow(seed("sc3").withMessageGroup(g).withSequence(3));
        LIFECYCLE.claimPending(10, Set.of());

        int released = LIFECYCLE.releaseStaleClaims(List.of(mine), Duration.ZERO);

        assertThat(released).isEqualTo(2);
        assertThat(claimedAt(mine)).as("held in memory: not released").isNotNull();
        assertThat(claimedAt(dead1)).isNull();
        assertThat(claimedAt(dead2)).isNull();
        assertThat(ids(LIFECYCLE.claimPending(10, Set.of()))).containsExactly(dead1, dead2);
    }

    @Test
    void thePeriodicReleaseOnlyTouchesClaimsOlderThanItsThreshold() {
        String g = "age-" + RUN;
        String fresh = seedWriteRow(seed("ag1").withMessageGroup(g).withSequence(1));
        String old = seedWriteRow(seed("ag2").withMessageGroup(g).withSequence(2));
        String oldButHeld = seedWriteRow(seed("ag3").withMessageGroup(g).withSequence(3));
        LIFECYCLE.claimPending(10, Set.of());
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '6 minutes' WHERE job_id = ANY(?::text[])",
                (Object) new String[] {old, oldButHeld});

        int released = LIFECYCLE.releaseStaleClaims(List.of(oldButHeld), Duration.ofMinutes(5));

        assertThat(released).isEqualTo(1);
        assertThat(claimedAt(fresh)).as("a claim from a moment ago is live").isNotNull();
        assertThat(claimedAt(old)).isNull();
        assertThat(claimedAt(oldButHeld)).as("old but this process still holds it").isNotNull();
    }

    // ── reconcile ──────────────────────────────────────────────────────────

    private static Duration THIRTY = Duration.ofSeconds(30);

    @Test
    void aConsistentTableReportsZeroAndChangesNothing() {
        String g = "rc-" + RUN;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(seedWriteRow(seed("rc" + i).withMessageGroup(g).withSequence(i)));
        LIFECYCLE.claimPending(2, Set.of());
        // jobs old enough for every age guard, claims too: a consistent table needs no repair whatever the guards
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = claimed_at - interval '1 hour' WHERE claimed_at IS NOT NULL");
        List<Map<String, Object>> before = ids.stream().map(DispatchJobFixture::queueRow).toList();

        var result = LIFECYCLE.reconcileQueue(5000, Duration.ZERO, Duration.ZERO);

        assertThat(result.isClean()).as("%s", result).isTrue();
        assertThat(ids.stream().map(DispatchJobFixture::queueRow).toList()).isEqualTo(before);
        assertThat(LIFECYCLE.queueDrift()).isEqualTo(new QueueDrift(0, 0));
    }

    /// (a) A PENDING job with no queue row gets one — but not a job that is still being written.
    /// Mutant: remove reconcile (a).
    @Test
    void reconcileInsertsTheMissingQueueRowOfAPendingJobAndLeavesAYoungOneAlone() {
        String missing = seedWriteRowOnly(seed("miss").withMessageGroup("miss-" + RUN).withUpdatedAt(Instant.now().minusSeconds(3600)));
        String young = seedWriteRowOnly(seed("young").withMessageGroup("miss-" + RUN).withSequence(2));
        assertThat(queueRow(missing)).isNull();
        assertThat(LIFECYCLE.queueDrift(List.of(missing, young)).missingOrStale()).isEqualTo(2);

        var result = LIFECYCLE.reconcileQueue(5000, THIRTY, Duration.ofMinutes(5));

        assertThat(result.inserted()).as("only the old one").isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertThat(result.refreshed()).isZero();
        assertQueueMirrorsJob(missing, "reconciled");
        assertThat(queueRow(young)).as("younger than the age guard: left alone").isNull();
        assertThat(ids(LIFECYCLE.claimPending(10, Set.of()))).as("and the repaired row is claimable").containsExactly(missing);
    }

    /// (b) A queue row whose job is not PENDING (or missing) goes — when unclaimed or its claim is old.
    @Test
    void reconcileDeletesQueueRowsOfJobsThatAreNotPendingUnlessALiveClaimHoldsThem() {
        String g = "del-" + RUN;
        String completed = seedWriteRow(seed("d1").withMessageGroup(g).withSequence(1));
        String completedLiveClaim = seedWriteRow(seed("d2").withMessageGroup(g).withSequence(2));
        String completedOldClaim = seedWriteRow(seed("d3").withMessageGroup(g).withSequence(3));
        String live = seedWriteRow(seed("d4").withMessageGroup(g).withSequence(4));
        String ghost = Tsid.generate();
        DB.execute("INSERT INTO msg_dispatch_queue (job_id, job_created_at, sequence, mode, version) VALUES (?, now(), 1, 'IMMEDIATE', now())", ghost);
        // the jobs move on behind the lifecycle's back, leaving their queue rows
        DB.execute("UPDATE msg_dispatch_jobs SET status = 'COMPLETED' WHERE id = ANY(?::text[])",
                (Object) new String[] {completed, completedLiveClaim, completedOldClaim});
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() WHERE job_id = ?", completedLiveClaim);
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '10 minutes' WHERE job_id = ?", completedOldClaim);

        var result = LIFECYCLE.reconcileQueue(5000, THIRTY, Duration.ofMinutes(5));

        assertThat(result.deleted()).as("the unclaimed orphan, the old-claim orphan and the ghost").isEqualTo(3);
        assertThat(queueRow(completed)).isNull();
        assertThat(queueRow(completedOldClaim)).isNull();
        assertThat(queueRow(ghost)).as("a job that does not exist").isNull();
        assertThat(queueRow(completedLiveClaim)).as("a live claim may be a delivery in progress: kept until it ages").isNotNull();
        assertQueueMirrorsJob(live, "untouched");
        // the live-claim orphan goes once its claim is old
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '10 minutes' WHERE job_id = ?", completedLiveClaim);
        assertThat(LIFECYCLE.reconcileQueue(5000, THIRTY, Duration.ofMinutes(5)).deleted()).isEqualTo(1);
    }

    /// (c) A queue row whose version differs from its job's updated_at is refreshed from the job.
    @Test
    void reconcileRefreshesAStaleQueueRowFromItsJob() {
        String g = "ref-" + RUN;
        String stale = seedWriteRow(seed("r1").withMessageGroup(g).withSequence(1));
        String fine = seedWriteRow(seed("r2").withMessageGroup(g).withSequence(2));
        // the job moved behind the lifecycle's back: new group, new sequence, new version
        DB.execute("UPDATE msg_dispatch_jobs SET message_group = ?, sequence = 9, updated_at = now() WHERE id = ?", g + "-moved", stale);
        DB.execute("UPDATE msg_dispatch_queue SET claimed_at = now() - interval '10 minutes' WHERE job_id = ?", stale);
        Map<String, Object> fineBefore = queueRow(fine);

        var result = LIFECYCLE.reconcileQueue(5000, THIRTY, Duration.ofMinutes(5));

        assertThat(result.refreshed()).isEqualTo(1);
        assertQueueMirrorsJob(stale, "refreshed from its job");
        assertThat(queueRow(fine)).isEqualTo(fineBefore);
    }

    @Test
    void reconcileIsBoundedByItsRowLimitPerStatement() {
        for (int i = 0; i < 7; i++) {
            seedWriteRowOnly(seed("bound" + i).withMessageGroup("bound-" + RUN).withSequence(i).withUpdatedAt(Instant.now().minusSeconds(3600)));
        }
        assertThat(LIFECYCLE.reconcileQueue(3, THIRTY, Duration.ofMinutes(5)).inserted()).isEqualTo(3);
        assertThat(LIFECYCLE.reconcileQueue(3, THIRTY, Duration.ofMinutes(5)).inserted()).isEqualTo(3);
        assertThat(LIFECYCLE.reconcileQueue(3, THIRTY, Duration.ofMinutes(5)).inserted()).isEqualTo(1);
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
        assertThat(ids(LIFECYCLE.claimPending(10, Set.of()))).containsExactly(staleQueued);
    }

    // ── backlog ────────────────────────────────────────────────────────────

    @Test
    void theBacklogCountsOnlyUnclaimedDueRowsAndReportsTheOldest() {
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

        LIFECYCLE.claimPending(1, Set.of());
        assertThat(LIFECYCLE.queueBacklog().depth()).as("a claimed row is no longer waiting").isEqualTo(1);
    }
}

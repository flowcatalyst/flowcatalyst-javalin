package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ClaimRow;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [DispatchLanes] driven directly, with scripted publishers and hand-built
/// claims (no seeded rows: the status update simply matches nothing, which the
/// lanes count). Everything here is about order, generations and bookkeeping;
/// the database-backed behaviour is in [PendingJobPollerTest].
///
/// Every test submits claims the way the poller does — `nextGeneration()`, then
/// `submit(rows, generation)` — and steers the lane with [ScriptedPublisher]
/// gates, so the interleavings the generation rule exists for are deterministic.
class DispatchLanesTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private final List<DispatchLanes> toClose = new ArrayList<>();
    private SchedulerMetrics metrics;

    @AfterEach
    void closeLanes() {
        toClose.forEach(DispatchLanes::close);
        toClose.clear();
    }

    private DispatchLanes lanes(SchedulerConfig config, DispatchPublisher publisher) {
        return lanes(config, publisher, System::nanoTime);
    }

    private DispatchLanes lanes(SchedulerConfig config, DispatchPublisher publisher,
                                java.util.function.LongSupplier clock) {
        metrics = new SchedulerMetrics(config.dispatchers());
        var lanes = new DispatchLanes(config, new DispatchJobLifecycle(DATA_SOURCE), publisher,
                DispatchLanesTest::message, metrics, clock);
        lanes.start();
        toClose.add(lanes);
        return lanes;
    }

    private static SchedulerConfig config(int dispatchers, int laneBatch) {
        return SchedulerConfig.DEFAULTS.withDispatchers(dispatchers).withLaneBatch(laneBatch)
                .withBufferCapacity(10_000);
    }

    private static ClaimRow row(String id, String group) {
        return new ClaimRow(id, null, group, DispatchMode.IMMEDIATE, null, null, Instant.now(), 0, null, Instant.now());
    }

    private static PublishedMessage message(ClaimRow c) {
        String group = c.messageGroup() == null || c.messageGroup().isEmpty() ? null : c.messageGroup();
        return new PublishedMessage(c.id(), c.createdAt(), c.clientId(), c.subscriptionId(), c.queue(),
                new Message(c.id(), "pool", "token", null, MediationType.HTTP, "http://localhost/x", group, false,
                        c.mode()));
    }

    /// One poller-style claim: the permits, then the generation, then the rows.
    /// (The buffer is far larger than any claim here, so the permits are never
    /// short and the accounting is real: idle means every permit is back.)
    private static long claim(DispatchLanes lanes, ClaimRow... rows) {
        try {
            assertThat(lanes.acquirePermits(rows.length)).isEqualTo(rows.length);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        long generation = lanes.nextGeneration();
        lanes.submit(List.of(rows), generation);
        return generation;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    // ── order across many claims and lanes ──────────────────────────────────

    /// Seven groups (coprime with the four lanes, so a round-robin router would
    /// move each group from lane to lane), twenty-five claims, four lanes: every group's jobs are
    /// published in claim order, whichever lane they landed in. Mutant: route
    /// grouped jobs round-robin instead of by group hash — a group's jobs are
    /// spread over lanes that run independently, and the order breaks.
    @Test
    void aGroupsJobsArePublishedInOrderAcrossManyClaimsAndLanes() {
        var publisher = new ScriptedPublisher().jitter(3);
        var lanes = lanes(config(4, 100), publisher);
        int groups = 7;
        int rounds = 25;
        for (int r = 0; r < rounds; r++) {
            var rows = new ArrayList<ClaimRow>();
            for (int g = 0; g < groups; g++) {
                rows.add(row(String.format("g%d-j%02d", g, r), "group-" + g));
            }
            claim(lanes, rows.toArray(ClaimRow[]::new));
        }

        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        for (int g = 0; g < groups; g++) {
            var expected = new ArrayList<String>();
            for (int r = 0; r < rounds; r++) expected.add(String.format("g%d-j%02d", g, r));
            assertThat(publisher.publishedOf("g" + g + "-")).as("group %d", g).containsExactlyElementsOf(expected);
        }
        assertThat(lanes.inFlightCount()).isZero();
    }

    /// A publish fails midway through a group (one job at a time, so the rest of
    /// the group is behind it in the channel and is dropped by the poison
    /// rule, not skipped by the publisher): the later jobs are dropped, and once
    /// they are claimed again everything is published in order, exactly once.
    /// Mutant: no poison — jobs 4..9 are published ahead of the failed job 3.
    @Test
    void aFailureMidwayDropsTheGroupsLaterJobsAndThenEverythingIsRepublishedInOrder() throws Exception {
        var publisher = new ScriptedPublisher().failOnce("a-j03").gate("a-j00");
        var lanes = lanes(config(1, 1), publisher);
        var first = new ArrayList<ClaimRow>();
        for (int i = 0; i < 10; i++) first.add(row("a-j0" + i, "group-a"));
        claim(lanes, first.toArray(ClaimRow[]::new)); // the lane is held in a-j00's publish, so 1..9 sit in its channel
        assertThat(publisher.awaitEntered("a-j00")).isTrue();
        publisher.open("a-j00");
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(publisher.published()).as("0,1,2 went out; 3 failed; 4..9 were dropped, not overtaking")
                .containsExactly("a-j00", "a-j01", "a-j02");
        assertThat(lanes.inFlightCount()).isZero();

        // The poller claims the still-PENDING rows again, in order.
        var again = new ArrayList<ClaimRow>();
        for (int i = 3; i < 10; i++) again.add(row("a-j0" + i, "group-a"));
        claim(lanes, again.toArray(ClaimRow[]::new));

        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(publisher.published()).containsExactly("a-j00", "a-j01", "a-j02", "a-j03", "a-j04", "a-j05",
                "a-j06", "a-j07", "a-j08", "a-j09");
        assertThat(lanes.poisonedGroups()).as("the first job past the poison cleared it").isZero();
    }

    // ── the generation rule ─────────────────────────────────────────────────

    /// The concurrent-claim case, driven deterministically. A claim that
    /// started (incremented the generation, snapshotted the in-flight set)
    /// BEFORE a failure was handled returns later jobs of the failed job's
    /// group: they must be dropped. A claim that starts AFTER snapshots the set
    /// without the failed job, so it returns it again, in order, and everything
    /// passes.
    ///
    /// Mutants, each of which fails this test: poison set to the failed job's
    /// own generation instead of the current one (the claim taken during the
    /// failure slips through); no poison at all; `<=` weakened to `<`; the
    /// entry never cleared by a job that passes (the republish is dropped).
    @Test
    void aClaimTakenBeforeAFailureIsHandledCannotOvertakeAndAClaimTakenAfterPasses() throws Exception {
        var publisher = new ScriptedPublisher().failOnce("a1").gate("a1");
        var lanes = lanes(config(1, 1), publisher);

        long gen1 = claim(lanes, row("a1", "group-a")); // the lane picks it up and blocks in the publish
        assertThat(publisher.awaitEntered("a1")).isTrue();
        // A second claim runs while a1's failure has not been handled yet: its
        // snapshot contains a1, so it returns a2 and a3 and not a1.
        long gen2 = claim(lanes, row("a2", "group-a"), row("a3", "group-a"));
        assertThat(gen2).isGreaterThan(gen1);

        publisher.open("a1"); // a1 now fails
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(publisher.published()).as("a2 and a3 were dropped rather than published ahead of a1").isEmpty();
        assertThat(lanes.inFlightCount()).as("ids leave the set on failure and on drop").isZero();
        assertThat(lanes.availablePermits()).isEqualTo(10_000);

        // A claim that starts after the failure was handled: a1 is no longer in
        // flight, so it comes back with its successors, in order.
        long gen3 = claim(lanes, row("a1", "group-a"), row("a2", "group-a"), row("a3", "group-a"));
        assertThat(gen3).isGreaterThan(gen2);

        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(publisher.published()).containsExactly("a1", "a2", "a3");
        assertThat(lanes.poisonedGroups()).isZero();
    }

    /// A drop does NOT renew the poison. `j2` (doomed) is dropped; a job of the
    /// group from a claim taken meanwhile (generation newer than the poison)
    /// passes and clears it. Renewing the poison on a drop closes the overtaking
    /// hole too, but livelocks when claims outpace a lane's drain: every claim
    /// made while a batch is dropped is older than the renewed poison and is
    /// dropped in turn. (At the lane level the pass shows up as a publish; the
    /// poller-side check below is what keeps it from overtaking.)
    /// Mutant: poison the group again when a job is dropped.
    @Test
    void aDropDoesNotRenewThePoison() throws Exception {
        for (int laneBatch : new int[]{1, 100}) {
            var publisher = new ScriptedPublisher().failOnce("j1").gate("j1").gate("x");
            var lanes = lanes(config(1, laneBatch), publisher);

            claim(lanes, row("j1", "group-j"));
            assertThat(publisher.awaitEntered("j1")).isTrue();
            assertThat(lanes.acquirePermits(2)).isEqualTo(2);
            long gen2 = lanes.nextGeneration();
            lanes.submit(List.of(row("x", null)), gen2);
            publisher.open("j1"); // j1 fails (poison = 2); the lane takes x and blocks in it
            assertThat(publisher.awaitEntered("x")).isTrue();
            awaitTrue(() -> lanes.poisonedGroups() == 1, "j1's failure to be recorded");
            lanes.submit(List.of(row("j2", "group-j")), gen2); // doomed: generation <= the poison
            claim(lanes, row("j1", "group-j")); // a claim taken after: generation > the poison
            publisher.open("x");
            assertThat(lanes.awaitIdle(WAIT)).isTrue();

            assertThat(publisher.publishedOf("j")).as("laneBatch=%d: j2 was dropped, the newer j1 passed", laneBatch)
                    .containsExactly("j1");
            assertThat(lanes.poisonedGroups()).as("and cleared the poison").isZero();
            lanes.close();
        }
    }

    /// The claim must not skip a doomed job. `j2` waits in a lane, doomed (its
    /// generation is `<=` the poison `j1`'s failure set); a later claim excludes
    /// it as in flight and returns the rows behind it, `j1` and `j3` — which
    /// would be published ahead of `j2`. The poller-side check drops the group's
    /// rows from that claim (they stay PENDING); ungrouped rows and groups with
    /// no doomed job pass; and once the doomed job has gone the group passes.
    /// Mutant: no check.
    @Test
    void aClaimThatSawADoomedInFlightJobDoesNotSubmitTheJobsBehindIt() throws Exception {
        var publisher = new ScriptedPublisher().failOnce("j1").gate("j1");
        var lanes = lanes(config(1, 1), publisher);
        var poisoned = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        lanes.afterPoisonHook = () -> {
            poisoned.countDown();
            try {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        claim(lanes, row("j1", "group-j"), row("j2", "group-j"));
        assertThat(publisher.awaitEntered("j1")).isTrue();
        publisher.open("j1"); // j1 fails; the lane holds in the settle with j2 doomed in its channel
        assertThat(poisoned.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(lanes.acquirePermits(4)).isEqualTo(4);
        lanes.nextGeneration();
        var snapshot = lanes.inFlightSnapshot();
        var claimed = List.of(row("j1", "group-j"), row("j3", "group-j"), row("k1", "group-k"), row("u1", null));
        var submitted = lanes.withoutGroupsBehindDoomedJobs(claimed, snapshot);
        assertThat(submitted.stream().map(ClaimRow::id)).as("nothing behind the doomed j2; other groups pass")
                .containsExactly("k1", "u1");

        release.countDown();
        lanes.releasePermits(4);
        assertThat(lanes.awaitIdle(WAIT)).isTrue(); // j2 was dropped
        lanes.afterPoisonHook = null;
        var later = lanes.inFlightSnapshot();
        assertThat(lanes.withoutGroupsBehindDoomedJobs(claimed, later)).as("the doomed job has gone")
                .hasSameSizeAs(claimed);
    }

    /// Ungrouped jobs are never poisoned: a failure of one does not drop the
    /// next, whatever its generation.
    @Test
    void anUngroupedFailureNeverDropsAnotherJob() throws Exception {
        var publisher = new ScriptedPublisher().failOnce("u1").gate("u1");
        var lanes = lanes(config(1, 1), publisher);

        claim(lanes, row("u1", null));
        assertThat(publisher.awaitEntered("u1")).isTrue();
        claim(lanes, row("u2", null), row("u3", ""));
        publisher.open("u1");
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(publisher.published()).containsExactly("u2", "u3");
        assertThat(lanes.poisonedGroups()).isZero();
    }

    /// A group nobody claims again is forgotten after the TTL, so the map does
    /// not grow with every group that ever failed.
    @Test
    void aPoisonedGroupNobodyClaimsAgainIsEvictedAfterTheTtl() throws Exception {
        var now = new AtomicLong(0);
        var publisher = new ScriptedPublisher().failOnce("e1");
        var lanes = lanes(config(1, 1), publisher, now::get);

        claim(lanes, row("e1", "group-e"));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(lanes.poisonedGroups()).isEqualTo(1);

        now.addAndGet(DispatchLanes.POISON_TTL.toNanos() - 1);
        Thread.sleep(200); // several idle wake-ups of the lane
        assertThat(lanes.poisonedGroups()).as("still inside the TTL").isEqualTo(1);

        now.addAndGet(Duration.ofMinutes(2).toNanos());
        awaitTrue(() -> lanes.poisonedGroups() == 0, "the poison entry to be evicted");
    }

    // ── permits and the in-flight set ──────────────────────────────────────

    /// Permits come back to the buffer capacity and ids leave the in-flight set
    /// on every path: published, left unpublished by the publisher, dropped for
    /// a poisoned group. Mutants: not removing a dropped job's id from the set,
    /// or not releasing its permit, leaves them behind.
    @Test
    void idsLeaveTheInFlightSetAndPermitsReturnOnSuccessFailureAndDrop() throws Exception {
        var publisher = new ScriptedPublisher().failOnce("f1").gate("f1");
        var config = SchedulerConfig.DEFAULTS.withDispatchers(1).withLaneBatch(1).withBufferCapacity(20);
        var lanes = lanes(config, publisher);

        claim(lanes, row("f1", "group-f"));
        assertThat(publisher.awaitEntered("f1")).isTrue();
        claim(lanes, row("f2", "group-f"), row("ok1", null), row("ok2", "group-ok"));
        assertThat(lanes.inFlightCount()).as("f1 (blocked) + f2 + ok1 + ok2").isEqualTo(4);
        assertThat(lanes.availablePermits()).isEqualTo(16);
        publisher.open("f1");
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(publisher.published()).as("f1 failed, f2 was dropped").containsExactlyInAnyOrder("ok1", "ok2");
        assertThat(metrics.unpublishedTotal()).isEqualTo(1);
        assertThat(metrics.droppedPoisoned()).isEqualTo(1);
        assertThat(lanes.inFlightCount()).isZero();
        assertThat(lanes.availablePermits()).isEqualTo(20);
    }

    @Test
    void aPublisherThatThrowsSomethingUnexpectedStillSettlesTheBatch() {
        DispatchPublisher exploding = batch -> {
            throw new IllegalStateException("boom");
        };
        var lanes = lanes(config(1, 100), exploding);

        claim(lanes, row("b1", "group-b"), row("b2", "group-b"), row("b3", null));

        assertThat(lanes.awaitIdle(WAIT)).as("permits are released even when the publisher blows up").isTrue();
        assertThat(lanes.inFlightCount()).isZero();
        assertThat(lanes.takeFailure()).as("and the poller is told to back off").isTrue();
        assertThat(lanes.takeFailure()).as("the report is consumed").isFalse();
    }

    /// A failure is reported to the poller (for its back-off) once, and a clean
    /// batch does not report one.
    @Test
    void aFailedBatchIsReportedToThePollerAndACleanOneIsNot() throws Exception {
        var publisher = new ScriptedPublisher().failOnce("r1");
        var lanes = lanes(config(1, 1), publisher);

        claim(lanes, row("r0", null));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(lanes.takeFailure()).isFalse();

        claim(lanes, row("r1", null));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(lanes.takeFailure()).isTrue();
    }

    /// A publisher exception that names no unpublished job is read as "the whole
    /// call failed": nothing is marked QUEUED (a QUEUED row without a message is
    /// the one outcome nothing recovers).
    @Test
    void anExceptionNamingNoJobsLeavesTheWholeBatchUnpublished() throws Exception {
        DispatchPublisher vague = batch -> {
            throw new DispatchPublisher.PublishException("vague", null, List.of());
        };
        var lanes = lanes(config(1, 100), vague);

        claim(lanes, row("v1", null), row("v2", null));

        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(lanes.takeFailure()).isTrue();
        assertThat(metrics.unpublishedTotal()).isEqualTo(2);
        assertThat(metrics.publishedTotal()).isZero();
    }

    /// Shutdown: a lane finishes the batch it is in the middle of and then
    /// exits; what is still in its channel is never published.
    @Test
    void closingLetsTheLaneFinishItsBatchAndLeavesTheChannelUnpublished() throws Exception {
        var publisher = new ScriptedPublisher().gate("s1");
        var lanes = lanes(config(1, 1), publisher);

        claim(lanes, row("s1", null));
        assertThat(publisher.awaitEntered("s1")).isTrue();
        claim(lanes, row("s2", null), row("s3", null));

        var closer = new Thread(lanes::close);
        long start = System.nanoTime();
        closer.start();
        Thread.sleep(150); // the lane is still inside s1's publish
        assertThat(closer.isAlive()).as("close waits for the batch in progress").isTrue();
        publisher.open("s1");
        closer.join(10_000);
        assertThat(closer.isAlive()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));

        assertThat(publisher.published()).as("s1 finished; s2 and s3 were left in the channel").containsExactly("s1");
    }

    /// Hot-path sanity: many ungrouped jobs spread over the lanes all get
    /// published exactly once.
    @Test
    void ungroupedJobsAreSpreadOverTheLanesAndEachPublishedOnce() {
        var publisher = new ScriptedPublisher();
        var lanes = lanes(config(5, 7), publisher);
        var ids = new ArrayList<String>();
        for (int c = 0; c < 20; c++) {
            var rows = new ArrayList<ClaimRow>();
            for (int i = 0; i < 25; i++) {
                String id = "u" + c + "-" + i;
                ids.add(id);
                rows.add(row(id, null));
            }
            claim(lanes, rows.toArray(ClaimRow[]::new));
        }
        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(publisher.published()).containsExactlyInAnyOrderElementsOf(ids);
        Map<String, Integer> counts = new HashMap<>();
        publisher.published().forEach(id -> counts.merge(id, 1, Integer::sum));
        assertThat(counts.values()).allMatch(n -> n == 1);
    }

    // ── releasing the claims of what is not published ───────────────────────

    /// What the lanes asked to release, in order, and what they asked to mark `QUEUED`.
    private static final class Recorder {
        final List<String> released = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> marked = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean failMark;
        volatile boolean failRelease;

        int mark(List<ClaimRow> rows) {
            if (failMark) throw new IllegalStateException("mark down");
            rows.forEach(r -> marked.add(r.id()));
            return rows.size();
        }

        void release(List<String> ids) {
            events.add("release");
            if (failRelease) throw new IllegalStateException("release down");
            released.addAll(ids);
        }
    }

    private DispatchLanes lanesWith(SchedulerConfig config, DispatchPublisher publisher, Recorder recorder) {
        metrics = new SchedulerMetrics(config.dispatchers());
        var lanes = new DispatchLanes(config, recorder::mark, recorder::release, publisher, DispatchLanesTest::message,
                metrics, System::nanoTime);
        lanes.start();
        toClose.add(lanes);
        return lanes;
    }

    /// Published jobs leave the queue (the QUEUED update deletes their rows) and are NOT released; a job
    /// the broker did not accept, and the later jobs of its group that the publisher withheld, are.
    /// Mutant: no release on a failed publish — the failed job is never claimed again.
    @Test
    void aFailedPublishReleasesTheClaimsOfTheUnpublishedJobsOnly() throws Exception {
        var recorder = new Recorder();
        var publisher = new ScriptedPublisher().failOnce("g-j2");
        var lanes = lanesWith(config(1, 100), publisher, recorder);

        claim(lanes, row("g-j1", "group-g"), row("g-j2", "group-g"), row("g-j3", "group-g"), row("o-j1", "group-o"));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(publisher.published()).containsExactlyInAnyOrder("g-j1", "o-j1");
        assertThat(recorder.marked).containsExactlyInAnyOrder("g-j1", "o-j1");
        assertThat(recorder.released).as("the failed job and the one behind it").containsExactlyInAnyOrder("g-j2", "g-j3");
        assertThat(metrics.claimsReleased.sum()).isEqualTo(2);
    }

    /// The jobs a lane drops because their group was poisoned give their claims back too.
    /// Mutant: drop without releasing — the dropped rows stay claimed until the stale sweep.
    @Test
    void aPoisonedDropReleasesTheDroppedJobsClaims() throws Exception {
        var recorder = new Recorder();
        var publisher = new ScriptedPublisher().failOnce("a-j03").gate("a-j00");
        var lanes = lanesWith(config(1, 1), publisher, recorder);
        var first = new ArrayList<ClaimRow>();
        for (int i = 0; i < 10; i++) first.add(row("a-j0" + i, "group-a"));
        claim(lanes, first.toArray(ClaimRow[]::new));
        assertThat(publisher.awaitEntered("a-j00")).isTrue();
        publisher.open("a-j00");
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(recorder.marked).containsExactly("a-j00", "a-j01", "a-j02");
        assertThat(recorder.released).as("3 failed; 4..9 were dropped: every one of them is claimed again")
                .containsExactlyInAnyOrder("a-j03", "a-j04", "a-j05", "a-j06", "a-j07", "a-j08", "a-j09");
        assertThat(metrics.droppedPoisoned()).isEqualTo(6);
    }

    /// A job published but whose QUEUED update failed is still PENDING: its claim goes back, so it is
    /// published again (a harmless duplicate). Mutant: leave it claimed — it is never sent again.
    @Test
    void aFailedQueuedUpdateReleasesTheClaimsOfTheJobsItWasMarking() throws Exception {
        var recorder = new Recorder();
        recorder.failMark = true;
        var lanes = lanesWith(config(1, 100), new ScriptedPublisher(), recorder);

        claim(lanes, row("m-j1", "group-m"), row("m-j2", "group-m"));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(recorder.released).containsExactlyInAnyOrder("m-j1", "m-j2");
        assertThat(lanes.poisonedGroups()).as("published, so nothing overtakes: not poisoned").isZero();
    }

    /// The release comes BEFORE the poison generation is read (the ordering rule): by the time the
    /// between-removal-and-poison hook runs, the claims are already back. Mutant: release after the poison.
    @Test
    void theClaimsAreReleasedBeforeThePoisonGenerationIsRead() throws Exception {
        var recorder = new Recorder();
        var lanes = lanesWith(config(1, 100), new ScriptedPublisher().failOnce("o-j1"), recorder);
        lanes.betweenRemovalAndPoisonHook = () -> recorder.events.add("hook");

        claim(lanes, row("o-j1", "group-o"));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(recorder.events).containsExactly("release", "hook");
        assertThat(lanes.inFlightCount()).isZero();
    }

    /// A release that fails (database down) must not stall the lane: permits come back, the failure
    /// is counted, and the stale-claim sweep gives the claims back later.
    @Test
    void aFailingReleaseIsCountedAndDoesNotStallTheLane() throws Exception {
        var recorder = new Recorder();
        recorder.failRelease = true;
        var lanes = lanesWith(config(1, 100), new ScriptedPublisher().failOnce("r-j1"), recorder);

        claim(lanes, row("r-j1", "group-r"));
        assertThat(lanes.awaitIdle(WAIT)).as("the permits came back").isTrue();

        assertThat(metrics.releaseErrors.sum()).isEqualTo(1);
        assertThat(lanes.inFlightCount()).isZero();
        // and the lane still works
        recorder.failRelease = false;
        claim(lanes, row("r-j2", "group-r"));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(recorder.marked).contains("r-j2");
    }

    /// Closing the lanes gives back the claims of everything still in flight (best effort).
    @Test
    void closingReleasesTheClaimsStillInFlight() {
        var recorder = new Recorder();
        metrics = new SchedulerMetrics(1);
        var lanes = new DispatchLanes(config(1, 100), recorder::mark, recorder::release, new ScriptedPublisher(),
                DispatchLanesTest::message, metrics, System::nanoTime); // never started: the jobs sit in the channel
        claim(lanes, row("c-j1", "group-c"), row("c-j2", null));

        lanes.close();

        assertThat(recorder.released).containsExactlyInAnyOrder("c-j1", "c-j2");
    }

    /// A lane settles only its OWN in-flight entry. The release runs before the batch leaves the in-flight
    /// set, so a job it released can be claimed again in that window: the new claim's entry must survive the
    /// old batch's removal, or the copy waiting in the lane is invisible to the poller's doomed check and later
    /// claims take the group's later jobs past it. Driven deterministically: the hook between the release and
    /// the removal plays a whole new claim of the released job.
    /// Mutant: remove the in-flight entry by id alone — it also removes the new claim's entry.
    @Test
    void aLaneRemovesOnlyItsOwnInFlightEntryNotTheEntryOfAClaimThatReclaimedTheJob() throws Exception {
        var recorder = new Recorder();
        var publisher = new ScriptedPublisher().failOnce("a-j1");
        var lanes = lanesWith(config(1, 1), publisher, recorder);
        var reclaimed = new java.util.concurrent.atomic.AtomicBoolean();
        lanes.afterReleaseHook = () -> {
            if (reclaimed.compareAndSet(false, true)) {
                claim(lanes, row("a-j1", "group-a")); // the poller claims the released job again
            }
        };
        var inFlightAfterSettle = new java.util.concurrent.atomic.AtomicInteger(-1);
        var minGenerationSeen = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Long>>();
        var observed = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        lanes.afterPoisonHook = () -> {
            if (inFlightAfterSettle.get() < 0) { // the first settle only: the old batch is settled, the new copy waits
                inFlightAfterSettle.set(lanes.inFlightCount());
                minGenerationSeen.set(lanes.inFlightSnapshot().minGeneration());
                observed.countDown();
                try {
                    proceed.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        claim(lanes, row("a-j1", "group-a"));
        assertThat(observed.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(inFlightAfterSettle.get()).as("the re-claim's copy is still in flight").isEqualTo(1);
        assertThat(minGenerationSeen.get()).as("and visible to the doomed check").containsKey("group-a");
        proceed.countDown();
        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(lanes.inFlightCount()).isZero();
    }
}

package io.flowcatalyst.platform.scheduler;

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
        var lanes = new DispatchLanes(config, new DispatchJobRepository(DATA_SOURCE), publisher,
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

    /// The hole the generation rule alone leaves, found while implementing it:
    /// with `j1 < j2 < j3`, `j2` claimed (so in the in-flight set) before `j1`
    /// fails, a claim taken right after the failure returns `j1` and `j3` — never
    /// `j2`, which is "in flight". Both pass the generation test, `j2` is then
    /// dropped, and `j3` would be published ahead of `j2`. A dropped job
    /// therefore poisons its group again, at the generation read after it leaves
    /// the set, and drops the rest of its group in the same batch.
    ///
    /// Run with `laneBatch = 1` (`j2`, `j1`, `j3` are separate batches: mutant "a
    /// dropped job does not re-poison" fails) and `laneBatch = 100` (one batch:
    /// mutant "the rest of a dropped job's group in the batch is not dropped"
    /// fails).
    @Test
    void aJobDroppedForAPoisonedGroupKeepsItPoisonedSoALaterJobCannotOvertakeIt() throws Exception {
        for (int laneBatch : new int[]{1, 100}) {
            var publisher = new ScriptedPublisher().failOnce("j1").gate("j1").gate("x");
            var lanes = lanes(config(1, laneBatch), publisher);

            long gen1 = claim(lanes, row("j1", "group-j"));
            assertThat(publisher.awaitEntered("j1")).isTrue();
            // Claim 2 started while j1 was in flight: it returns an ungrouped x and j2.
            // Its submit is slow: x is in the channel, j2 not yet.
            assertThat(lanes.acquirePermits(2)).isEqualTo(2);
            long gen2 = lanes.nextGeneration();
            lanes.submit(List.of(row("x", null)), gen2);
            publisher.open("j1"); // j1 fails (poison = 2); the lane takes x and blocks in it
            assertThat(publisher.awaitEntered("x")).isTrue();
            awaitTrue(() -> lanes.poisonedGroups() == 1, "j1's failure to be recorded");
            assertThat(lanes.inFlightSnapshot()).as("j1 has left the in-flight set").doesNotContain("j1");
            lanes.submit(List.of(row("j2", "group-j")), gen2); // claim 2's last row arrives

            // Claim 3 starts after j1's failure was handled: it returns j1 and j3 but
            // not j2 (still in flight).
            long gen3 = claim(lanes, row("j1", "group-j"), row("j3", "group-j"));
            assertThat(gen3).isGreaterThan(gen2).isGreaterThan(gen1);
            publisher.open("x");
            assertThat(lanes.awaitIdle(WAIT)).isTrue();

            assertThat(publisher.publishedOf("j")).as("laneBatch=%d: nothing of the group went out ahead of j2", laneBatch)
                    .isEmpty();
            assertThat(publisher.published()).containsExactly("x");

            // Claim 4: everything still PENDING, in order.
            claim(lanes, row("j1", "group-j"), row("j2", "group-j"), row("j3", "group-j"));
            assertThat(lanes.awaitIdle(WAIT)).isTrue();
            assertThat(publisher.publishedOf("j")).as("laneBatch=%d", laneBatch).containsExactly("j1", "j2", "j3");
            lanes.close();
        }
    }

    /// Interleaving (b), driven deterministically: a claim runs in the window
    /// between a failing batch leaving the in-flight set and the lane reading the
    /// generation to poison with. The claim increments the generation and
    /// snapshots the set; because the failed job's id is already gone, it
    /// returns the whole group in order — and the poison, read afterwards,
    /// covers it. Mutant: read the generation BEFORE removing the ids (the
    /// window is then after the read, so the claim's snapshot still holds the
    /// failed job, it returns only its successors, and they pass the generation
    /// test and overtake it).
    @Test
    void aClaimRunningWhileAFailureSettlesCannotOvertakeWhicheverStepComesFirst() throws Exception {
        var publisher = new ScriptedPublisher().failOnce("w1").gate("w1");
        var lanes = lanes(config(1, 1), publisher);
        var group = List.of(row("w1", "group-w"), row("w2", "group-w"), row("w3", "group-w"));
        var window = new java.util.concurrent.atomic.AtomicBoolean();
        lanes.betweenRemovalAndPoisonHook = () -> {
            if (!window.compareAndSet(false, true)) return;
            try {
                // A whole poller claim, on this thread, inside the window: the
                // PENDING rows are all three; the in-flight ones are excluded.
                assertThat(lanes.acquirePermits(3)).isEqualTo(3);
                long generation = lanes.nextGeneration();
                var snapshot = lanes.inFlightSnapshot();
                var rows = group.stream().filter(r -> !snapshot.contains(r.id())).toList();
                lanes.releasePermits(3 - rows.size());
                lanes.submit(rows, generation);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        };

        claim(lanes, group.get(0));
        assertThat(publisher.awaitEntered("w1")).isTrue();
        publisher.open("w1"); // w1 fails; the claim runs in the settle window
        awaitTrue(window::get, "the claim in the settle window");
        assertThat(lanes.awaitIdle(WAIT)).isTrue();

        assertThat(publisher.published()).as("nothing of the group overtook w1").isEmpty();

        claim(lanes, group.toArray(ClaimRow[]::new));
        assertThat(lanes.awaitIdle(WAIT)).isTrue();
        assertThat(publisher.published()).containsExactly("w1", "w2", "w3");
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
}

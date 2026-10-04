package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [DispatchScheduler#start] itself: fails closed without a usable
/// `FLOWCATALYST_APP_KEY` (dispatch-seam spec §11), and — like
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobReaperTest]'s
/// equivalent test for the reaper — actually runs the claim loop on a
/// background thread rather than only when called directly through
/// [PendingJobPoller#pollOnce].
class DispatchSchedulerTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);
    private static final String PROCESSING_ENDPOINT = "http://localhost:18080/api/dispatch/process";

    @Test
    void startReturnsNullWithoutAUsableAppKey() {
        assertThat(DispatchScheduler.start(null, PROCESSING_ENDPOINT, DATA_SOURCE,
                FakeDispatchPublisher.succeeding(), () -> true)).isNull();
        assertThat(DispatchScheduler.start("", PROCESSING_ENDPOINT, DATA_SOURCE,
                FakeDispatchPublisher.succeeding(), () -> true)).isNull();
        assertThat(DispatchScheduler.start("   ", PROCESSING_ENDPOINT, DATA_SOURCE,
                FakeDispatchPublisher.succeeding(), () -> true)).isNull();
    }

    @Test
    void startRunsTheClaimLoopOnABackgroundThreadWithoutBeingCalledDirectly() throws InterruptedException {
        String jobId = seedWriteRow(Seed.of(code("scheduler-start-" + RUN)));

        try (var scheduler = DispatchScheduler.start("test-app-key-" + RUN, PROCESSING_ENDPOINT, DATA_SOURCE,
                FakeDispatchPublisher.succeeding(), () -> true, 5000)) {
            assertThat(scheduler).isNotNull();
            long deadline = System.currentTimeMillis() + 5000;
            DispatchJobStatus status = DispatchJobStatus.PENDING;
            while (System.currentTimeMillis() < deadline && status == DispatchJobStatus.PENDING) {
                Thread.sleep(50);
                status = REPO.findById(jobId).orElseThrow().status();
            }
            assertThat(status).as("the started scheduler claimed the row on its own, not via a direct pollOnce() call")
                    .isEqualTo(DispatchJobStatus.QUEUED);
        }
    }

    /// Owner ruling 2026-09-22 (`docs/spec/router-hol-deferral.md`): there is
    /// no stale-`QUEUED` recovery any more. A row the broker has held for an
    /// hour — a message the router deferred for a full pool, or one the broker
    /// expired — stays `QUEUED`; nothing re-publishes it. (Before: reverted to
    /// `PENDING` after 5 minutes and re-published, a second copy every 5
    /// minutes for the whole deferral.)
    @Test
    void aRowQueuedForAnHourIsNeverRevertedOrRePublished() throws InterruptedException {
        String jobId = seedWriteRow(Seed.of(code("scheduler-stale-" + RUN))
                .withStatus("QUEUED")
                .withUpdatedAt(java.time.Instant.now().minus(java.time.Duration.ofHours(1))));
        var publisher = FakeDispatchPublisher.succeeding();

        try (var scheduler = DispatchScheduler.start("test-app-key-" + RUN, PROCESSING_ENDPOINT, DATA_SOURCE,
                publisher, () -> true, 5000)) {
            assertThat(scheduler).isNotNull();
            Thread.sleep(1500); // more than one poll tick
            assertThat(REPO.findById(jobId).orElseThrow().status())
                    .as("mutant: a stale-QUEUED sweep reverts it").isEqualTo(DispatchJobStatus.QUEUED);
            assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId).toList())
                    .as("never re-published").doesNotContain(jobId);
        }
    }

    // ── backlog drain: a full, productive tick is followed at once ─────────

    /// Six full batches of 5 must drain well inside the 1s poll delay's
    /// reach: with the delay between passes, 6 passes cost 5+ seconds.
    /// Mutant: pollSafely runs one tick per scheduled run.
    @Test
    void aBacklogOfFullBatchesDrainsWithoutTheDelayBetweenThem() throws InterruptedException {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 30; i++) {
            ids.add(seedWriteRow(Seed.of(code("drain" + i + "-"))));
        }
        var publisher = FakeDispatchPublisher.succeeding();

        long start = System.nanoTime();
        try (var scheduler = DispatchScheduler.start("test-app-key-" + RUN, PROCESSING_ENDPOINT, DATA_SOURCE,
                publisher, () -> true, 5)) {
            assertThat(scheduler).isNotNull();
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && !allQueued(ids)) {
                Thread.sleep(20);
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(allQueued(ids)).as("all 30 jobs published").isTrue();
            assertThat(elapsedMillis).as("6 full batches without a 1s sleep between them").isLessThan(3000);
        }
    }

    /// A full claim that publishes nothing would claim the same rows again, so
    /// it must wait out the delay. Mutant: re-poll on `claimed == batch` alone.
    @Test
    void aFullClaimThatPublishesNothingDoesNotRePollImmediately() throws InterruptedException {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            ids.add(seedWriteRow(Seed.of(code("nopublish" + i + "-")).withMessageGroup("aaa-nopublish-" + RUN)
                    .withSequence(i)));
        }
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        DispatchPublisher refusing = batch -> {
            attempts.incrementAndGet();
            throw new DispatchPublisher.PublishException("refused", null,
                    batch.stream().map(PublishedMessage::jobId).toList());
        };

        try (var scheduler = DispatchScheduler.start("test-app-key-" + RUN, PROCESSING_ENDPOINT, DATA_SOURCE,
                refusing, () -> true, 5)) {
            assertThat(scheduler).isNotNull();
            Thread.sleep(600); // inside the 1s delay: only the first tick can have run
            assertThat(attempts.get()).as("one tick, then the fixed delay").isEqualTo(1);
        } finally {
            for (String id : ids) { // leave nothing PENDING for the other tests
                DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.STATUS, "COMPLETED")
                        .where(MSG_DISPATCH_JOBS.ID.eq(id)).execute();
            }
        }
    }

    @Test
    void aNonLeaderDoesNothingAndDoesNotSpin() throws InterruptedException {
        String id = seedWriteRow(Seed.of(code("nonleader-")));
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var publisher = FakeDispatchPublisher.succeeding();

        try (var scheduler = DispatchScheduler.start("test-app-key-" + RUN, PROCESSING_ENDPOINT, DATA_SOURCE,
                publisher, () -> { checks.incrementAndGet(); return false; }, 1)) {
            assertThat(scheduler).isNotNull();
            Thread.sleep(600);
            assertThat(checks.get()).as("one leadership check, then the fixed delay").isEqualTo(1);
        } finally {
            assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
            DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.STATUS, "COMPLETED")
                    .where(MSG_DISPATCH_JOBS.ID.eq(id)).execute();
        }
        assertThat(publisher.batches()).isEmpty();
    }

    private static boolean allQueued(List<String> ids) {
        return ids.stream().allMatch(id -> REPO.findById(id).orElseThrow().status() == DispatchJobStatus.QUEUED);
    }
}

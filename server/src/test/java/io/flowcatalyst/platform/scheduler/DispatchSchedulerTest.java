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

    /// A broker that refuses everything leaves its rows PENDING, so every
    /// claim would return them again: without a back-off the poller retries it
    /// in a hot loop. Mutant: no back-off after a lane failure / a short claim —
    /// hundreds of attempts inside the window instead of a handful.
    @Test
    void aFailingBrokerIsNotRetriedInAHotLoop() throws InterruptedException {
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
            Thread.sleep(700); // inside the 1s delay: a first claim or two, then the back-off
            int early = attempts.get();
            assertThat(early).as("a claim or two, then the poll interval").isBetween(1, 4);
            Thread.sleep(200);
            assertThat(attempts.get()).as("nothing more inside the same interval").isEqualTo(early);
        } finally {
            for (String id : ids) { // leave nothing PENDING for the other tests
                DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.STATUS, "COMPLETED")
                        .where(MSG_DISPATCH_JOBS.ID.eq(id)).execute();
            }
        }
    }

    /// A claim that errors (database down) is logged and waited out, not
    /// retried at once. Mutant: no back-off on error.
    @Test
    void aFailingClaimIsNotRetriedInAHotLoop() throws InterruptedException {
        var connections = new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource down = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                DispatchSchedulerTest.class.getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (p, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        connections.incrementAndGet();
                        throw new java.sql.SQLException("database is down");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        try (var scheduler = DispatchScheduler.start("test-app-key-" + RUN, PROCESSING_ENDPOINT, down,
                FakeDispatchPublisher.succeeding(), () -> true, 5)) {
            assertThat(scheduler).isNotNull();
            Thread.sleep(600);
            // The paused-connection cache and the claim each ask once per poll.
            assertThat(connections.get()).as("one poll, then the fixed delay").isBetween(1, 3);
            assertThat(scheduler.poller().metrics().pollErrors()).isEqualTo(1);
        }
    }

    /// Shutdown mid-batch: close() returns promptly (once the batch in the
    /// middle of its publish finishes), every job the publisher accepted is
    /// QUEUED, and everything else — still in a channel, never taken — is left
    /// PENDING for the next leader. Mutant: lanes that keep draining their
    /// channels after close (nothing stays PENDING), or that exit mid-batch
    /// (a published job stays PENDING).
    @Test
    void shutdownMidBatchLeavesUnpublishedJobsPendingAndReturnsPromptly() throws Exception {
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            ids.add(seedWriteRow(Seed.of(code("shutdown" + i + "-"))));
        }
        var entered = new java.util.concurrent.CountDownLatch(1);
        var open = new java.util.concurrent.CountDownLatch(1);
        var accepted = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        DispatchPublisher held = batch -> {
            entered.countDown();
            try {
                if (!open.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("never opened");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            batch.forEach(m -> accepted.add(m.jobId()));
        };
        var config = SchedulerConfig.DEFAULTS.withBatchSize(5).withDispatchers(1);
        try {
            var scheduler = DispatchScheduler.start("test-app-key-" + RUN, PROCESSING_ENDPOINT, DATA_SOURCE,
                    held, () -> true, config);
            assertThat(scheduler).isNotNull();
            assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // Let the poller claim ahead while the lane is held: 12 rows in claims of 5.
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && scheduler.poller().lanes().inFlightCount() < 12) {
                Thread.sleep(10);
            }
            assertThat(scheduler.poller().lanes().inFlightCount()).as("all 12 claimed and buffered").isGreaterThanOrEqualTo(12);

            var closer = new Thread(scheduler::close);
            long start = System.nanoTime();
            closer.start();
            Thread.sleep(150);
            open.countDown(); // the batch in progress finishes
            closer.join(10_000);
            assertThat(closer.isAlive()).as("close returned").isFalse();
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));

            var queued = ids.stream().filter(id -> REPO.findById(id).orElseThrow().status() == DispatchJobStatus.QUEUED).toList();
            var pending = ids.stream().filter(id -> REPO.findById(id).orElseThrow().status() == DispatchJobStatus.PENDING).toList();
            assertThat(queued).as("exactly what the broker accepted is QUEUED").containsExactlyInAnyOrderElementsOf(accepted);
            assertThat(pending).as("the rest was never published and is PENDING").hasSize(12 - accepted.size());
            assertThat(pending).as("something was still buffered at shutdown").isNotEmpty();
        } finally {
            open.countDown();
            for (String id : ids) {
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

package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.Seed;
import io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.platform.dispatchjob.settled.HmacTokenVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_JOBS;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.DB;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// The poller and its lanes together, against the database, with a publisher
/// the test holds shut: the buffer's back-pressure and the in-flight exclusion.
/// Its own class, so its own database — the row counts here are exact.
class PendingJobPollerConcurrencyTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);
    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DATA_SOURCE);
    private static final HmacTokenVerifier AUTH = HmacTokenVerifier.fromAppKey("test-app-key-" + RUN);
    private static final String ENDPOINT = "http://localhost:18080/api/dispatch/process";
    private static final Duration WAIT = Duration.ofSeconds(15);

    private final List<PendingJobPoller> pollers = new ArrayList<>();

    private PendingJobPoller poller(DispatchPublisher publisher, SchedulerConfig config) {
        var poller = new PendingJobPoller(DATA_SOURCE, REPO, LIFECYCLE, new PausedConnectionCache(DATA_SOURCE),
                new PoolCodeResolver(DATA_SOURCE), publisher, AUTH, ENDPOINT, () -> true, config);
        pollers.add(poller);
        return poller;
    }

    @AfterEach
    void cleanUp() {
        pollers.forEach(PendingJobPoller::close);
        pollers.clear();
        DB.update(MSG_DISPATCH_JOBS).set(MSG_DISPATCH_JOBS.STATUS, "COMPLETED")
                .where(MSG_DISPATCH_JOBS.STATUS.in("PENDING", "QUEUED")).execute();
    }

    private static List<String> seed(int n, String tag) {
        var ids = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            ids.add(seedWriteRow(Seed.of(code(tag + i + "-"))));
        }
        return ids;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    /// A publisher that holds every call until the test opens it.
    private static final class Held implements DispatchPublisher {
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch firstCall = new CountDownLatch(1);
        private final List<String> accepted = java.util.Collections.synchronizedList(new ArrayList<>());

        List<String> published() {
            synchronized (accepted) {
                return List.copyOf(accepted);
            }
        }

        @Override
        public void publish(List<PublishedMessage> batch) {
            firstCall.countDown();
            try {
                if (!open.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never opened");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            batch.forEach(m -> accepted.add(m.jobId()));
        }
    }

    /// The poller blocks only when the buffer is full, and resumes when a lane
    /// releases permits. Buffer of 4, claims of 4: the first claim fills the
    /// buffer (the lane is held in its publish), the second must wait for a
    /// permit — not claim, not spin — and goes on once the lane has settled.
    /// Mutants: not acquiring permits (the second claim does not wait), a lane
    /// that never releases them (it never resumes).
    @Test
    void thePollerBlocksWhenTheBufferIsFullAndResumesWhenALaneReleasesPermits() throws Exception {
        List<String> ids = seed(8, "buf");
        var held = new Held();
        var config = SchedulerConfig.DEFAULTS.withBufferCapacity(4).withDispatchers(1).withBatchSize(4);
        var poller = poller(held, config);

        var first = poller.pollOnce();
        assertThat(first.claimed()).isEqualTo(4);
        assertThat(held.firstCall.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(poller.lanes().availablePermits()).as("the buffer is full").isZero();

        var second = new AtomicReference<PendingJobPoller.PollResult>();
        var thread = new Thread(() -> second.set(poller.pollOnce()), "second-poll");
        thread.start();
        Thread.sleep(400);
        assertThat(thread.isAlive()).as("the poller waits for a permit").isTrue();
        assertThat(poller.metrics().claims()).as("and has not claimed again").isEqualTo(1);

        held.open.countDown(); // the lane publishes, marks QUEUED, releases its permits
        thread.join(15_000);
        assertThat(thread.isAlive()).as("the poller resumed").isFalse();
        // How many rows it claimed depends on how the lane split its batches (a
        // lane that took one row first releases one permit first); the rest follow.
        assertThat(second.get().claimed()).isBetween(1, 4);
        assertThat(poller.awaitIdle(WAIT)).isTrue();
        for (int i = 0; i < 20 && held.published().size() < ids.size(); i++) {
            poller.pollOnce();
            assertThat(poller.awaitIdle(WAIT)).isTrue();
        }

        assertThat(held.published()).containsExactlyInAnyOrderElementsOf(ids);
        for (String id : ids) {
            assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        }
    }

    /// While a claimed row is in flight no claim returns it again, and once
    /// settled it is not claimed again either (it is QUEUED). Ten rows, claims
    /// of five, publisher held: claim 1 takes five, claim 2 the other five,
    /// claim 3 nothing; every id is published exactly once.
    /// Mutant: claim without the in-flight ids — claim 2 returns claim 1's rows.
    @Test
    void noJobIsSubmittedTwiceWhileItIsInFlight() throws Exception {
        List<String> ids = seed(10, "dup");
        var held = new Held();
        var config = SchedulerConfig.DEFAULTS.withBufferCapacity(100).withDispatchers(2).withBatchSize(5);
        var poller = poller(held, config);

        var one = poller.pollOnce();
        var two = poller.pollOnce();
        var three = poller.pollOnce();

        assertThat(one.claimed()).isEqualTo(5);
        assertThat(two.claimed()).as("the first claim's rows are in flight, so excluded").isEqualTo(5);
        assertThat(three.claimed()).as("nothing left that is not in flight").isZero();
        assertThat(poller.lanes().inFlightCount()).isEqualTo(10);

        held.open.countDown();
        assertThat(poller.awaitIdle(WAIT)).isTrue();

        assertThat(held.published()).containsExactlyInAnyOrderElementsOf(ids).doesNotHaveDuplicates();
        assertThat(poller.lanes().inFlightCount()).isZero();
        assertThat(poller.lanes().availablePermits()).as("permits are back at the buffer capacity").isEqualTo(100);
        assertThat(poller.pollOnce().claimed()).as("QUEUED rows are not claimed again").isZero();
    }

    /// Interleaving (a), driven deterministically through the real poller: a
    /// claim has taken its snapshot (so it excludes the in-flight `j1`) when
    /// `j1`'s publish fails and is settled. The generation must have been taken
    /// BEFORE the snapshot: then it is `<=` the poison and the claim's `j2` is
    /// dropped. Mutant: increment the generation after the snapshot — it is
    /// then `>` the poison, `j2` passes and is published ahead of the failed `j1`.
    @Test
    void aClaimThatSnapshottedBeforeAFailureWasHandledCannotOvertakeTheFailedJob() throws Exception {
        String group = "grp-gen-" + RUN;
        var ids = new ArrayList<String>();
        for (int i = 1; i <= 3; i++) {
            ids.add(seedWriteRow(Seed.of(code("gen" + i + "-")).withMessageGroup(group).withSequence(i)));
        }
        String j1 = ids.get(0);
        var publisher = new ScriptedPublisher().failOnce(j1).gate(j1);
        var config = SchedulerConfig.DEFAULTS.withBufferCapacity(100).withDispatchers(1).withLaneBatch(1)
                .withBatchSize(1);
        var poller = poller(publisher, config);

        assertThat(poller.pollOnce().claimed()).isEqualTo(1); // j1: the lane blocks in its publish
        assertThat(publisher.awaitEntered(j1)).isTrue();

        var snapshotTaken = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        poller.lanes().afterSnapshotHook = () -> {
            snapshotTaken.countDown();
            try {
                proceed.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var second = new Thread(poller::pollOnce, "claim-with-a-stale-snapshot");
        second.start();
        assertThat(snapshotTaken.await(15, TimeUnit.SECONDS)).isTrue(); // snapshot holds j1; the claim has not run

        publisher.open(j1); // j1 fails and its batch is settled
        awaitTrue(() -> poller.lanes().poisonedGroups() == 1, "j1's failure to be settled");
        poller.lanes().afterSnapshotHook = null;
        proceed.countDown(); // the claim runs: it excludes j1 and returns j2
        second.join(15_000);
        assertThat(second.isAlive()).isFalse();
        assertThat(poller.awaitIdle(WAIT)).isTrue();

        assertThat(publisher.published()).as("j2 was dropped, not published ahead of j1").isEmpty();
        for (String id : ids) {
            assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        }

        // The next claims publish the group in order.
        for (int i = 0; i < 20 && publisher.published().size() < 3; i++) {
            poller.pollOnce();
            assertThat(poller.awaitIdle(WAIT)).isTrue();
        }
        assertThat(publisher.published()).containsExactlyElementsOf(ids);
    }

    /// The poller-side half of the ordering rule, through the real poller. `j2`
    /// is in a lane's channel and DOOMED (`j1`, ahead of it, failed and poisoned
    /// the group); a claim taken now excludes `j2` as in flight and finds `j1`
    /// and `j3` — newer than the poison, so the lane would let them through
    /// ahead of `j2`. The poller must not submit them: they stay PENDING.
    /// Mutant: remove the check from the poller.
    @Test
    void thePollerDoesNotSubmitTheJobsBehindADoomedInFlightJob() throws Exception {
        String group = "grp-doomed-" + RUN;
        var ids = new ArrayList<String>();
        for (int i = 1; i <= 3; i++) {
            ids.add(seedWriteRow(Seed.of(code("doom" + i + "-")).withMessageGroup(group).withSequence(i)));
        }
        String j1 = ids.get(0);
        var publisher = new ScriptedPublisher().failOnce(j1).gate(j1);
        var config = SchedulerConfig.DEFAULTS.withBufferCapacity(100).withDispatchers(1).withLaneBatch(1)
                .withBatchSize(2);
        var poller = poller(publisher, config);
        var poisoned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        poller.lanes().afterPoisonHook = () -> {
            poisoned.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        assertThat(poller.pollOnce().submitted()).isEqualTo(2); // j1 and j2
        assertThat(publisher.awaitEntered(j1)).isTrue();
        publisher.open(j1); // j1 fails; the lane holds with j2 doomed behind it
        assertThat(poisoned.await(15, TimeUnit.SECONDS)).isTrue();

        var second = poller.pollOnce();
        assertThat(second.claimed()).as("j1 and j3: j2 is excluded as in flight").isEqualTo(2);
        assertThat(second.submitted()).as("nothing behind the doomed j2 is submitted").isZero();
        assertThat(second.backOff()).as("it will be claimable again in moments: no sleep").isFalse();
        assertThat(poller.metrics().skippedDoomed()).isEqualTo(2);

        poller.lanes().afterPoisonHook = null;
        release.countDown();
        assertThat(poller.awaitIdle(WAIT)).isTrue();
        assertThat(publisher.published()).as("nothing went out ahead of j2").isEmpty();

        for (int i = 0; i < 20 && publisher.published().size() < 3; i++) {
            poller.pollOnce();
            assertThat(poller.awaitIdle(WAIT)).isTrue();
        }
        assertThat(publisher.published()).containsExactlyElementsOf(ids);
    }
}

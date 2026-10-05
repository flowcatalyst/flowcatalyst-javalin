package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatchjob.DispatchJobFixture;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.code;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.queueRow;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRowOnly;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// The poller against the queue table: a claim (a delete) that is not published or is held back is RESTORED, and a
/// job a dead claimer left with no row is restored at leader start; the job is claimed again IN ORDER
/// (dispatch-queue spec step 3b). Its own class,
/// so its own database — the queue rows here are exact.
class PendingJobPollerReleaseTest {

    private static final DispatchJobRepository REPO = new DispatchJobRepository(DATA_SOURCE);
    private static final DispatchJobLifecycle LIFECYCLE = new DispatchJobLifecycle(DATA_SOURCE);
    private static final HmacTokenVerifier AUTH = HmacTokenVerifier.fromAppKey("test-app-key-" + RUN);
    private static final String ENDPOINT = "http://localhost:18080/api/dispatch/process";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final SchedulerConfig CONFIG = SchedulerConfig.DEFAULTS.withBufferCapacity(500).withBatchSize(500);

    private final List<PendingJobPoller> pollers = new ArrayList<>();

    private PendingJobPoller poller(DispatchPublisher publisher, java.util.function.BooleanSupplier leader) {
        var poller = new PendingJobPoller(DATA_SOURCE, REPO, LIFECYCLE, new PausedConnectionCache(DATA_SOURCE),
                new PoolCodeResolver(DATA_SOURCE), publisher, AUTH, ENDPOINT, leader, CONFIG);
        pollers.add(poller);
        return poller;
    }

    @AfterEach
    void cleanUp() {
        pollers.forEach(PendingJobPoller::close);
        pollers.clear();
        DispatchJobFixture.setStatusWhere("COMPLETED", "PENDING", "QUEUED", "PROCESSING", "FAILED");
    }

    private static PendingJobPoller.PollResult pollAndSettle(PendingJobPoller poller) {
        var result = poller.pollOnce();
        assertThat(poller.awaitIdle(WAIT)).as("the lanes went idle").isTrue();
        return result;
    }


    private static Seed seed(String tag, String group, int sequence) {
        return Seed.of(code(tag)).withMode("BLOCK_ON_ERROR").withMessageGroup(group).withSequence(sequence);
    }

    /// A failed publish: the job stays PENDING, its queue row is restored, and the NEXT poll publishes it.
    /// Mutant: no restore on failure — the job has no queue row and is never sent again (until reconcile).
    @Test
    void aFailedPublishGivesTheClaimBackAndTheNextPollPublishesTheJob() {
        String group = "rel-fail-" + RUN;
        String id = seedWriteRow(seed("fail", group, 1));

        pollAndSettle(poller(FakeDispatchPublisher.failing(), () -> true));

        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(queueRow(id)).as("the queue row was restored").isNotNull();

        var ok = FakeDispatchPublisher.succeeding();
        var second = poller(ok, () -> true);
        pollAndSettle(second);
        assertThat(ok.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(id);
        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
        assertThat(queueRow(id)).as("published: out of the queue").isNull();
    }

    /// Every job of a group is published in order across a failure in the middle: j2 fails once, j3 (behind it)
    /// must not be sent before it. Polls until all are published; the broker saw 1, 2, 3.
    @Test
    void afterAFailureTheGroupIsClaimedAgainInOrderAndPublishedInOrder() {
        String group = "rel-order-" + RUN;
        String j1 = seedWriteRow(seed("o1", group, 1));
        String j2 = seedWriteRow(seed("o2", group, 2));
        String j3 = seedWriteRow(seed("o3", group, 3));
        var publisher = new ScriptedPublisher().failOnce(j2);
        var poller = poller(publisher, () -> true);

        for (int i = 0; i < 6 && publisher.published().size() < 3; i++) {
            pollAndSettle(poller);
        }

        assertThat(publisher.published()).containsExactly(j1, j2, j3);
        assertThat(REPO.findById(j3).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
    }

    /// A BLOCK_ON_ERROR job behind a FAILED head is held back: nothing is published, the job stays PENDING and —
    /// the point here — its queue row is restored, so it is claimed (and held) again, and goes the moment the head
    /// clears. (The group is remembered as held for 5 seconds, so the second poll skips it: it still goes after.)
    /// Mutant: hold back without restoring — the job has no queue row and never moves again.
    @Test
    void aHeldBackJobGivesItsClaimBackAndFlowsOnceTheHeadClears() {
        String group = "rel-held-" + RUN;
        String head = seedWriteRow(seed("h1", group, 1).withStatus("FAILED"));
        String held = seedWriteRow(seed("h2", group, 2));
        var publisher = FakeDispatchPublisher.succeeding();
        var poller = poller(publisher, () -> true);

        var first = pollAndSettle(poller);

        assertThat(first.heldBack()).isEqualTo(1);
        assertThat(publisher.batches()).isEmpty();
        assertThat(REPO.findById(held).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(queueRow(held)).as("held, so its queue row went back").isNotNull();
        assertThat(pollAndSettle(poller).claimed()).as("the group is remembered as held: the claim skips it").isZero();
        assertThat(queueRow(held)).isNotNull();

        DispatchJobFixture.setStatus(head, "COMPLETED"); // the head is resolved
        poller.heldGroupsForTest().clear();               // (the 5 s memory has not run out; see HeldGroupsTest)
        pollAndSettle(poller);
        assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(held);
    }

    /// A job behind a backed-off PENDING head (a future scheduled_for, read from the QUEUE table) is held too.
    @Test
    void aJobBehindABackedOffPendingHeadIsHeldBackAndItsClaimReleased() {
        String group = "rel-backoff-" + RUN;
        seedWriteRow(seed("b1", group, 1).withScheduledFor(java.time.Instant.now().plusSeconds(600)));
        String behind = seedWriteRow(seed("b2", group, 2));
        var publisher = FakeDispatchPublisher.succeeding();

        var result = pollAndSettle(poller(publisher, () -> true));

        assertThat(result.heldBack()).isEqualTo(1);
        assertThat(publisher.batches()).isEmpty();
        assertThat(queueRow(behind)).isNotNull();
    }

    /// The first poll as leader restores the queue rows of PENDING jobs a dead claimer left with none, with no age
    /// guard: a job claimed a moment before the crash is published at once. Mutant: no restore at leader start.
    @Test
    void theFirstPollAsLeaderRestoresJobsADeadClaimerLeftWithNoQueueRow() {
        String id = seedWriteRowOnly(Seed.of(code("dead")).withMessageGroup("rel-dead-" + RUN)); // PENDING, no row, fresh
        var publisher = FakeDispatchPublisher.succeeding();

        pollAndSettle(poller(publisher, () -> true));

        assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(id);
    }

    /// Not the leader: nothing is restored or claimed. Becoming the leader later restores then.
    @Test
    void aNonLeaderRestoresNothingUntilItBecomesTheLeader() {
        String id = seedWriteRowOnly(Seed.of(code("dead2")).withMessageGroup("rel-dead2-" + RUN));
        var leader = new AtomicBoolean(false);
        var publisher = FakeDispatchPublisher.succeeding();
        var poller = poller(publisher, leader::get);

        pollAndSettle(poller);
        assertThat(queueRow(id)).as("a standby does not touch the queue").isNull();
        assertThat(publisher.batches()).isEmpty();

        leader.set(true);
        pollAndSettle(poller);
        assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(id);
    }

    /// A job in THIS process's in-flight set is not restored when leadership is regained: it is published once.
    /// Mutant: restore every PENDING job without a row at leader start, in-flight ones included — the held job is
    /// queued, claimed and sent twice.
    @Test
    void regainingLeadershipDoesNotRestoreAJobThisProcessHolds() throws Exception {
        String held = seedWriteRow(seed("mine", "rel-mine-" + RUN, 1));
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var accepted = java.util.Collections.synchronizedList(new ArrayList<String>());
        DispatchPublisher publisher = batch -> {
            entered.countDown();
            try {
                if (!gate.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never opened");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            batch.forEach(m -> accepted.add(m.jobId()));
        };
        var leader = new AtomicBoolean(true);
        var poller = poller(publisher, leader::get);

        poller.pollOnce(); // claims `held` (deletes its row); its lane is stuck in the publish
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(poller.lanes().inFlightCount()).isEqualTo(1);

        leader.set(false);
        poller.pollOnce();
        leader.set(true);
        poller.pollOnce(); // leader again: restores what it does not hold, not `held`

        assertThat(queueRow(held)).as("still in flight: not re-queued").isNull();
        gate.countDown();
        assertThat(poller.awaitIdle(WAIT)).isTrue();
        assertThat(accepted).containsExactly(held);
        assertThat(Set.copyOf(accepted)).hasSize(accepted.size());
    }

    /// The starvation test: more than a batch of held rows at the head of the order must not keep an unheld
    /// group behind them from being published. Without the 5-second held-group memory each claim takes the same
    /// batch of held rows, finds them held and puts them back, and never reaches the job behind.
    /// Mutant: remove the held-group memory — the later job is never published.
    @Test
    void aBatchOfHeldRowsAtTheHeadDoesNotStarveAnUnheldGroupBehindThem() {
        var config = SchedulerConfig.DEFAULTS.withBufferCapacity(500).withBatchSize(5);
        // 8 groups ("aaa..."), each a FAILED head and a held follower: 8 held rows sort ahead of everything
        for (int i = 0; i < 8; i++) {
            String g = "aaa-held-" + RUN + "-" + i;
            seedWriteRow(seed("hh" + i, g, 1).withStatus("FAILED"));
            seedWriteRow(seed("hf" + i, g, 2));
        }
        String free = seedWriteRow(Seed.of(code("free")).withMessageGroup("zzz-free-" + RUN));
        var publisher = FakeDispatchPublisher.succeeding();
        var poller = new PendingJobPoller(DATA_SOURCE, REPO, LIFECYCLE, new PausedConnectionCache(DATA_SOURCE),
                new PoolCodeResolver(DATA_SOURCE), publisher, AUTH, ENDPOINT, () -> true, config);
        pollers.add(poller);

        for (int i = 0; i < 6; i++) pollAndSettle(poller);

        assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId))
                .as("the free job behind the held rows is reached").containsExactly(free);
    }
}

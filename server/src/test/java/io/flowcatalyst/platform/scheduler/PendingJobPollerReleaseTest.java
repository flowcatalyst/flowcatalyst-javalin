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
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedQueued;
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// The poller against the queue table: a claim that is not published, is held back, or is left by a dead
/// claimer is given back and the job is claimed again IN ORDER (dispatch-queue spec §3, §7). Its own class,
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

    private static Object claimedAt(String id) {
        return queueRow(id).get("claimed_at");
    }

    private static Seed seed(String tag, String group, int sequence) {
        return Seed.of(code(tag)).withMode("BLOCK_ON_ERROR").withMessageGroup(group).withSequence(sequence);
    }

    /// A failed publish: the job stays PENDING, its claim is given back, and the NEXT poll publishes it.
    /// Mutant: no release on failure — the queue row stays claimed and the job is never sent again.
    @Test
    void aFailedPublishGivesTheClaimBackAndTheNextPollPublishesTheJob() {
        String group = "rel-fail-" + RUN;
        String id = seedWriteRow(seed("fail", group, 1));

        pollAndSettle(poller(FakeDispatchPublisher.failing(), () -> true));

        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(claimedAt(id)).as("the claim was released").isNull();

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
    /// the point here — its claim is released, so it is claimed (and held) again, and goes the moment the head clears.
    /// Mutant: hold back without releasing — the row stays claimed and never moves again.
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
        assertThat(claimedAt(held)).as("held, so its claim went back").isNull();
        assertThat(pollAndSettle(poller).heldBack()).as("claimed and held again: it is still reachable").isEqualTo(1);

        DispatchJobFixture.setStatus(head, "COMPLETED"); // the head is resolved
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
        assertThat(claimedAt(behind)).isNull();
    }

    /// The first poll as leader gives back every claim this process does not hold: a job a dead claimer
    /// left claimed is published at once. Mutant: no release at leader start.
    @Test
    void theFirstPollAsLeaderReleasesClaimsLeftByADeadClaimer() {
        String id = seedQueued(Seed.of(code("dead")).withMessageGroup("rel-dead-" + RUN)); // claimed, nobody holds it
        assertThat(claimedAt(id)).isNotNull();
        var publisher = FakeDispatchPublisher.succeeding();

        pollAndSettle(poller(publisher, () -> true));

        assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(id);
    }

    /// Not the leader: nothing is released or claimed. Becoming the leader later releases then.
    @Test
    void aNonLeaderReleasesNothingUntilItBecomesTheLeader() {
        String id = seedQueued(Seed.of(code("dead2")).withMessageGroup("rel-dead2-" + RUN));
        var leader = new AtomicBoolean(false);
        var publisher = FakeDispatchPublisher.succeeding();
        var poller = poller(publisher, leader::get);

        pollAndSettle(poller);
        assertThat(claimedAt(id)).as("a standby does not touch claims").isNotNull();
        assertThat(publisher.batches()).isEmpty();

        leader.set(true);
        pollAndSettle(poller);
        assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(id);
    }

    /// A job in THIS process's in-flight set is not released when leadership is regained: it is published once.
    /// Mutant: release every claim at leader start, in-flight ones included — the held job is sent twice.
    @Test
    void regainingLeadershipDoesNotReleaseAJobThisProcessHolds() throws Exception {
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

        poller.pollOnce(); // claims `held`; its lane is stuck in the publish
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(poller.lanes().inFlightCount()).isEqualTo(1);

        leader.set(false);
        poller.pollOnce();
        leader.set(true);
        poller.pollOnce(); // leader again: releases the claims it does not hold, not `held`

        assertThat(claimedAt(held)).as("still claimed: this process holds it in memory").isNotNull();
        gate.countDown();
        assertThat(poller.awaitIdle(WAIT)).isTrue();
        assertThat(accepted).containsExactly(held);
        assertThat(Set.copyOf(accepted)).hasSize(accepted.size());
    }
}

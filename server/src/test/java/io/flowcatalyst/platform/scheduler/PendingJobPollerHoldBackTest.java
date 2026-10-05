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
import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.seedWriteRow;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// The poller against the job table: a job that is not published or is held back stays `PENDING` and is claimed
/// again IN ORDER; held groups do not starve the claim (dispatch step 4). Its own class,
/// so its own database — the queue rows here are exact.
class PendingJobPollerHoldBackTest {

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

    /// A failed publish: the job stays PENDING and the NEXT poll publishes it.
    @Test
    void aFailedPublishLeavesTheJobPendingAndTheNextPollPublishesIt() {
        String group = "rel-fail-" + RUN;
        String id = seedWriteRow(seed("fail", group, 1));

        pollAndSettle(poller(FakeDispatchPublisher.failing(), () -> true));

        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);

        var ok = FakeDispatchPublisher.succeeding();
        var second = poller(ok, () -> true);
        pollAndSettle(second);
        assertThat(ok.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(id);
        assertThat(REPO.findById(id).orElseThrow().status()).isEqualTo(DispatchJobStatus.QUEUED);
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

    /// A BLOCK_ON_ERROR job behind a FAILED head is held back: nothing is published, the job stays PENDING, the
    /// group is remembered as held (the second poll skips it), and the job goes the moment the head clears.
    @Test
    void aHeldBackJobStaysPendingAndFlowsOnceTheHeadClears() {
        String group = "rel-held-" + RUN;
        String head = seedWriteRow(seed("h1", group, 1).withStatus("FAILED"));
        String held = seedWriteRow(seed("h2", group, 2));
        var publisher = FakeDispatchPublisher.succeeding();
        var poller = poller(publisher, () -> true);

        var first = pollAndSettle(poller);

        assertThat(first.heldBack()).isEqualTo(1);
        assertThat(publisher.batches()).isEmpty();
        assertThat(REPO.findById(held).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
        assertThat(pollAndSettle(poller).claimed()).as("the group is remembered as held: the claim skips it").isZero();

        DispatchJobFixture.setStatus(head, "COMPLETED"); // the head is resolved
        poller.heldGroupsForTest().clear();               // (the 5 s memory has not run out; see HeldGroupsTest)
        pollAndSettle(poller);
        assertThat(publisher.batches().stream().flatMap(List::stream).map(PublishedMessage::jobId)).containsExactly(held);
    }

    /// A job behind a backed-off PENDING head (a future scheduled_for, read from the QUEUE table) is held too.
    @Test
    void aJobBehindABackedOffPendingHeadIsHeldBackAndStaysPending() {
        String group = "rel-backoff-" + RUN;
        seedWriteRow(seed("b1", group, 1).withScheduledFor(java.time.Instant.now().plusSeconds(600)));
        String behind = seedWriteRow(seed("b2", group, 2));
        var publisher = FakeDispatchPublisher.succeeding();

        var result = pollAndSettle(poller(publisher, () -> true));

        assertThat(result.heldBack()).isEqualTo(1);
        assertThat(publisher.batches()).isEmpty();
        assertThat(REPO.findById(behind).orElseThrow().status()).isEqualTo(DispatchJobStatus.PENDING);
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

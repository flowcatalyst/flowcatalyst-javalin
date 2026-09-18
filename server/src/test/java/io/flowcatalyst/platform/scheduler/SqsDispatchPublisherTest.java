package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/// [SqsDispatchPublisher] against a real embedded Postgres for tenant/priority
/// resolution ([PoolCodeResolver], [SubscriptionPriorityCache]) and
/// [FakeSqsSendClient] for the AWS calls themselves (`docs/spec/deployed-dispatch.md`
/// §3, `docs/go-mirror/2026-09-12-dispatch-rulings.md` R2/R6/R7, O2).
class SqsDispatchPublisherTest {

    private static final String PREFIX = "FC-sqspub" + RUN;
    private static final String ACCOUNT_ID = "123456789012";
    private static final String REGION = "us-east-1";
    private static final DispatchQueueSettings SETTINGS =
            new DispatchQueueSettings(true, PREFIX, "", ACCOUNT_ID, REGION);

    private static String queueUrl(String tenant, String priority) {
        return "https://sqs." + REGION + ".amazonaws.com/" + ACCOUNT_ID + "/" + PREFIX + "-" + tenant + "-" + priority + ".fifo";
    }

    private static PublishedMessage published(String jobId, String clientId, String subscriptionId, String messageGroupId) {
        return published(jobId, clientId, subscriptionId, null, messageGroupId);
    }

    /// Overload carrying the job's own `queue` claim (dispatch-job-priority
    /// spec R4) — most tests in this class don't care and use the 4-arg
    /// overload above (`queue = null`).
    private static PublishedMessage published(String jobId, String clientId, String subscriptionId, String queue,
                                               String messageGroupId) {
        Message message = new Message(jobId, "pool-code", "auth-token", null, MediationType.HTTP,
                "http://localhost/api/dispatch/process", messageGroupId, false, DispatchMode.IMMEDIATE);
        return new PublishedMessage(jobId, Instant.now(), clientId, subscriptionId, queue, message);
    }

    private static SqsDispatchPublisher publisher(FakeSqsSendClient client) {
        return new SqsDispatchPublisher(client, SETTINGS, new PoolCodeResolver(DATA_SOURCE),
                new SubscriptionPriorityCache(DATA_SOURCE));
    }

    // ── (1) chunking preserves claim order within one destination ──────────

    /// Mutant this pins: a chunk size of anything other than 10 (SQS's own
    /// cap), or a chunker that reorders entries within a destination — either
    /// would still "chunk" but would fail one of the two assertions below.
    @Test
    void oneHundredJobsChunkToBatchesOfAtMostTenPreservingClaimOrderWithinTheDestination() throws Exception {
        FakeSqsSendClient client = new FakeSqsSendClient();
        List<PublishedMessage> batch = new ArrayList<>();
        List<String> claimOrder = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String jobId = "chunkjob-" + RUN + "-" + String.format("%03d", i);
            claimOrder.add(jobId);
            batch.add(published(jobId, null, null, "grp-" + jobId));
        }

        publisher(client).publish(batch);

        List<SendMessageBatchRequest> requests = client.sendRequests();
        assertThat(requests).as("100 jobs to one destination chunk into ceil(100/10) requests").hasSize(10);
        assertThat(requests).allSatisfy(r -> assertThat(r.entries()).hasSizeLessThanOrEqualTo(10));

        List<String> sentOrder = requests.stream()
                .flatMap(r -> r.entries().stream())
                .map(SendMessageBatchRequestEntry::id)
                .toList();
        assertThat(sentOrder).as("claim order preserved across the chunk boundaries").containsExactlyElementsOf(claimOrder);
    }

    // ── (2) routing to the composed (tenant, priority) queue ────────────────

    /// Mutant this pins: swapping the tenant/priority composition, or
    /// dropping the client-less "platform" fallback (R5), or ignoring a
    /// subscription's HIGH_PRIORITY value (R1/R7) — each sends to the wrong
    /// queue URL, which this reads directly off the fake's captured requests.
    @Test
    void jobsRouteToTheComposedQueueForTheirTenantAndPriority() throws Exception {
        String clientIdentifier = "sqspubacme" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String defaultSub = SchedulerFixture.subscriptionWithQueue(null);
        String highSub = SchedulerFixture.subscriptionWithQueue("HIGH_PRIORITY");

        FakeSqsSendClient client = new FakeSqsSendClient();
        publisher(client).publish(List.of(
                published("routeclient-" + RUN, clientId, defaultSub, "g1"),
                published("routeplatform-" + RUN, null, null, "g2"),
                published("routehigh-" + RUN, clientId, highSub, "g3")));

        List<String> queueUrlsSeen = client.sendRequests().stream().map(SendMessageBatchRequest::queueUrl).toList();
        assertThat(queueUrlsSeen)
                .as("a client job to its DEFAULT queue, a client-less job to the platform queue, "
                        + "and a HIGH_PRIORITY subscription's job to that client's high-priority queue")
                .containsExactlyInAnyOrder(
                        queueUrl(clientIdentifier, "DEFAULT"),
                        queueUrl("platform", "DEFAULT"),
                        queueUrl(clientIdentifier, "HIGH_PRIORITY"));
    }

    // ── (2b) both publishers resolve the same destination — anti-drift ─────

    /// **Asserted against the shared resolver, so this publisher and
    /// [PostgresQueuePublisherTest]'s mirror-image assertion cannot drift**
    /// (`docs/spec/deployed-dispatch.md` §3, unit D part 1). Mutant this
    /// pins: this publisher composing its own SQS queue URL inline instead
    /// of delegating tenant/priority resolution to
    /// [DispatchDestinationResolver] via [#destinationFor] — a hand-rolled
    /// composition that happened to agree for the simple cases above but
    /// diverged for a real client + HIGH_PRIORITY job would fail this
    /// comparison.
    @Test
    void resolvesExactlyWhatTheSharedResolverComputesForTheSameJob() throws Exception {
        String clientIdentifier = "sqspubshared" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String highSub = SchedulerFixture.subscriptionWithQueue("HIGH_PRIORITY");
        String jobId = "sqspub-shared-" + RUN;

        DispatchDestinationResolver resolver = new DispatchDestinationResolver(
                new PoolCodeResolver(DATA_SOURCE), new SubscriptionPriorityCache(DATA_SOURCE), SETTINGS);
        PublishedMessage message = published(jobId, clientId, highSub, "g");
        String expectedUrl = SETTINGS.queueUriFor(resolver.destinationFor(message));

        FakeSqsSendClient client = new FakeSqsSendClient();
        publisher(client).publish(List.of(message));

        assertThat(client.sendRequests()).hasSize(1);
        assertThat(client.sendRequests().getFirst().queueUrl())
                .as("SqsDispatchPublisher must resolve exactly what the shared resolver computes")
                .isEqualTo(expectedUrl);
    }

    // ── (3) legacy/unusable stored queue value reads as DEFAULT, never throws ─

    /// Mutant this pins: [io.flowcatalyst.platform.shared.dispatch.QueuePriority#forPublishing]
    /// (or a caller) throwing instead of defaulting on unrecognised text (R6)
    /// — this would either propagate out of `publish` or route to a
    /// `null`/`HIGH_PRIORITY` queue instead of `DEFAULT`.
    @Test
    void legacyUnusableSubscriptionQueueValueRoutesToDefaultWithoutThrowing() {
        String clientIdentifier = "legacyco" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String legacySub = SchedulerFixture.subscriptionWithQueue("workers-high");

        FakeSqsSendClient client = new FakeSqsSendClient();
        PublishedMessage message = published("legacyjob-" + RUN, clientId, legacySub, "g");

        assertThatCode(() -> publisher(client).publish(List.of(message))).doesNotThrowAnyException();

        assertThat(client.sendRequests()).hasSize(1);
        assertThat(client.sendRequests().getFirst().queueUrl()).isEqualTo(queueUrl(clientIdentifier, "DEFAULT"));
    }

    // ── (4) dedup id is unique per publish attempt (R2) ─────────────────────

    /// **R2's entire point.** A mutant that set `MessageDeduplicationId` to
    /// the bare job id would make both calls' ids identical, failing this
    /// assertion — and in real SQS FIFO would let the second (genuine
    /// re-publish) attempt be silently dropped as a duplicate of the first,
    /// stranding the job forever.
    @Test
    void dedupIdDiffersBetweenTwoPublishesOfTheSameJob() throws Exception {
        String jobId = "deduptest-" + RUN;
        FakeSqsSendClient client = new FakeSqsSendClient();
        SqsDispatchPublisher pub = publisher(client);

        pub.publish(List.of(published(jobId, null, null, "g")));
        pub.publish(List.of(published(jobId, null, null, "g")));

        List<String> dedupIds = client.sendRequests().stream()
                .flatMap(r -> r.entries().stream())
                .map(SendMessageBatchRequestEntry::messageDeduplicationId)
                .toList();
        assertThat(dedupIds).hasSize(2);
        assertThat(dedupIds.get(0)).as("never the bare job id (R2)").isNotEqualTo(jobId);
        assertThat(dedupIds.get(1)).isNotEqualTo(jobId);
        assertThat(dedupIds.get(0)).as("differs between two publish attempts of the SAME job")
                .isNotEqualTo(dedupIds.get(1));
        assertThat(dedupIds).allSatisfy(id -> assertThat(id).startsWith(jobId + ":"));
    }

    // ── (5) message group id: the job's own, or a per-job fallback ─────────

    /// Mutant this pins: falling back to a SHARED literal (e.g. `"UNGROUPED"`)
    /// for every group-less job instead of each job's own id — that would
    /// impose FIFO ordering between otherwise-unrelated jobs, which the
    /// distinctness assertion below would catch.
    @Test
    void groupedJobKeepsItsGroupAndGroupLessJobGetsItsOwnJobIdAsAnOrderingNeutralGroup() throws Exception {
        String groupedJobId = "groupedjob-" + RUN;
        String ungroupedJobId1 = "ungroupedjob1-" + RUN;
        String ungroupedJobId2 = "ungroupedjob2-" + RUN;
        FakeSqsSendClient client = new FakeSqsSendClient();

        assertThatCode(() -> publisher(client).publish(List.of(
                published(groupedJobId, null, null, "explicit-group-" + RUN),
                published(ungroupedJobId1, null, null, null),
                published(ungroupedJobId2, null, null, null))))
                .doesNotThrowAnyException();

        var entriesById = client.sendRequests().stream()
                .flatMap(r -> r.entries().stream())
                .collect(Collectors.toMap(SendMessageBatchRequestEntry::id, e -> e));

        assertThat(entriesById.get(groupedJobId).messageGroupId()).isEqualTo("explicit-group-" + RUN);
        assertThat(entriesById.get(ungroupedJobId1).messageGroupId()).isEqualTo(ungroupedJobId1);
        assertThat(entriesById.get(ungroupedJobId2).messageGroupId()).isEqualTo(ungroupedJobId2);
        assertThat(Set.of(entriesById.get(ungroupedJobId1).messageGroupId(), entriesById.get(ungroupedJobId2).messageGroupId()))
                .as("two distinct group-less jobs never share a group — no ordering imposed between them")
                .hasSize(2);
    }

    // ── (7) lazy creation: missing queue is created, then the send succeeds ─

    /// Mutant this pins: skipping `createQueue` (the retry would then fail
    /// again against a still-missing queue and this whole test would throw),
    /// or creating it without `FifoQueue=true` (a non-FIFO queue would refuse
    /// the FIFO-only fields this publisher always sets, in real SQS).
    @Test
    void missingQueueIsCreatedAsFifoAndTheSendThenSucceeds() throws Exception {
        FakeSqsSendClient client = new FakeSqsSendClient();
        String expectedUrl = queueUrl("platform", "DEFAULT");
        client.markQueueMissing(expectedUrl);
        String jobId = "lazycreate-" + RUN;

        assertThatCode(() -> publisher(client).publish(List.of(published(jobId, null, null, "g"))))
                .as("the queue-missing failure is recovered inside this ONE publish call")
                .doesNotThrowAnyException();

        assertThat(client.createQueueRequests()).hasSize(1);
        var createRequest = client.createQueueRequests().getFirst();
        assertThat(createRequest.queueName()).isEqualTo(PREFIX + "-platform-DEFAULT.fifo");
        assertThat(createRequest.attributesAsStrings().get(QueueAttributeName.FIFO_QUEUE.toString())).isEqualTo("true");

        assertThat(client.sendRequests()).as("the failed attempt, then one retry").hasSize(2);
        assertThat(client.sendRequests()).allSatisfy(r -> assertThat(r.queueUrl()).isEqualTo(expectedUrl));
    }

    // ── (owner follow-up) group-aware chunking: same group never shares a
    //    batch, a failed group poisons its own later siblings, and ordering
    //    survives across chunk boundaries ────────────────────────────────────

    /// Mutant this pins: removing the "close the chunk early on a repeated
    /// group" rule — the two jobs would then land in ONE `SendMessageBatch`
    /// call, which is exactly the shape that lets AWS's per-entry failure
    /// reporting deliver a group out of order (class doc, "this is NOT merely
    /// defensive").
    @Test
    void twoJobsSharingAMessageGroupAreNeverSentInTheSameBatch() throws Exception {
        String group = "shared-group-" + RUN;
        String job1 = "samegroup1-" + RUN;
        String job2 = "samegroup2-" + RUN;
        FakeSqsSendClient client = new FakeSqsSendClient();

        publisher(client).publish(List.of(published(job1, null, null, group), published(job2, null, null, group)));

        assertThat(client.sendRequests()).as("one job per group per chunk forces two separate batches").hasSize(2);
        assertThat(client.sendRequests().get(0).entries()).extracting(SendMessageBatchRequestEntry::id)
                .containsExactly(job1);
        assertThat(client.sendRequests().get(1).entries()).extracting(SendMessageBatchRequestEntry::id)
                .containsExactly(job2);
    }

    /// The owner-ruled correctness gap this follow-up closes. Mutant this
    /// pins: sending the sibling anyway (no poisoning) — the sibling would
    /// then appear in a request and NOT be in `unpublishedJobIds`, which both
    /// assertions below would catch independently: the "absent from every
    /// request" check would fail even if the revert bookkeeping happened to
    /// be right, and vice versa.
    @Test
    void aFailedGroupsLaterSiblingIsNeverSentAndBothAppearInUnpublishedJobIds() {
        String group = "poison-group-" + RUN;
        String failingJobId = "poisonfirst-" + RUN;
        String siblingJobId = "poisonsecond-" + RUN;
        FakeSqsSendClient client = new FakeSqsSendClient();
        client.failEntryOnce(failingJobId, "InternalError");

        List<PublishedMessage> batch = List.of(
                published(failingJobId, null, null, group),
                published(siblingJobId, null, null, group));

        DispatchPublisher.PublishException thrown = catchThrowableOfType(
                DispatchPublisher.PublishException.class, () -> publisher(client).publish(batch));

        assertThat(thrown).isNotNull();
        assertThat(thrown.unpublishedJobIds())
                .as("both the job SQS actually failed and its never-sent sibling revert")
                .containsExactlyInAnyOrder(failingJobId, siblingJobId);

        List<String> everySentJobId = client.sendRequests().stream()
                .flatMap(r -> r.entries().stream())
                .map(SendMessageBatchRequestEntry::id)
                .toList();
        assertThat(everySentJobId).as("the sibling is ABSENT from every request — not merely reverted afterward")
                .doesNotContain(siblingJobId)
                .contains(failingJobId);
    }

    /// The other half of group-poisoning: a THROWN chunk (not a per-entry
    /// `BatchResultErrorEntry`) must poison its group exactly like a
    /// per-entry failure does. Mutant this pins: only recording failed groups
    /// from `response.failed()` and never from the `catch (RuntimeException)`
    /// branch — the sibling would then be sent (and likely succeed), which
    /// both assertions below independently catch.
    @Test
    void aThrownChunkPoisonsItsGroupJustLikeAPerEntryFailureDoes() {
        String group = "throw-poison-group-" + RUN;
        String throwingJobId = "throwfirst-" + RUN;
        String siblingJobId = "throwsecond-" + RUN;
        FakeSqsSendClient client = new FakeSqsSendClient();
        client.failWholeSendContainingJobOnce(throwingJobId);

        List<PublishedMessage> batch = List.of(
                published(throwingJobId, null, null, group),
                published(siblingJobId, null, null, group));

        DispatchPublisher.PublishException thrown = catchThrowableOfType(
                DispatchPublisher.PublishException.class, () -> publisher(client).publish(batch));

        assertThat(thrown).isNotNull();
        assertThat(thrown.unpublishedJobIds())
                .as("both the job whose whole chunk threw and its never-sent sibling revert")
                .containsExactlyInAnyOrder(throwingJobId, siblingJobId);

        List<String> everySentJobId = client.sendRequests().stream()
                .flatMap(r -> r.entries().stream())
                .map(SendMessageBatchRequestEntry::id)
                .toList();
        assertThat(everySentJobId).as("the sibling is absent from every request — the group's poisoning, "
                        + "not just its own send attempt, is what kept it out")
                .doesNotContain(siblingJobId);
    }

    /// Mutant this pins: any reordering in the chunk-building loop (e.g.
    /// appending a later-arriving different-group job ahead of a still-live
    /// group member) — the flattened send order would then diverge from
    /// claim order even though every job was still (individually) delivered.
    @Test
    void aGroupsJobsAreSentInClaimOrderAcrossChunkBoundaries() throws Exception {
        String group = "ordered-group-" + RUN;
        String g1 = "ordered1-" + RUN;
        String g2 = "ordered2-" + RUN;
        String g3 = "ordered3-" + RUN;
        String other = "ordered-other-" + RUN;
        FakeSqsSendClient client = new FakeSqsSendClient();

        // g1/g2/g3 share a group and are adjacent in claim order, exactly as
        // `claimPending`'s `ORDER BY message_group, sequence, ...` produces;
        // `other` (a distinct group) follows.
        publisher(client).publish(List.of(
                published(g1, null, null, group),
                published(g2, null, null, group),
                published(g3, null, null, group),
                published(other, null, null, "other-group-" + RUN)));

        List<String> sentOrder = client.sendRequests().stream()
                .flatMap(r -> r.entries().stream())
                .map(SendMessageBatchRequestEntry::id)
                .toList();
        assertThat(sentOrder)
                .as("claim order preserved across the chunk boundaries this group's own repetition forces")
                .containsExactly(g1, g2, g3, other);
    }

    /// **The counter that must change.** Without group-aware chunking
    /// degrading gracefully for the ordinary case (distinct groups), a naive
    /// "close the chunk on ANY repeat, or on every entry" implementation
    /// could still pass every test above while silently sending one message
    /// per `SendMessageBatch` call always — which every other pinned test in
    /// this class is written in a way that would not catch, since none of
    /// them assert the REQUEST COUNT for a plain, no-collision batch this
    /// large. This one does.
    @Test
    void tenJobsWithTenDistinctGroupsStillFitInOneBatchOfTen() throws Exception {
        FakeSqsSendClient client = new FakeSqsSendClient();
        List<PublishedMessage> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String jobId = "distinctgroup-" + RUN + "-" + i;
            batch.add(published(jobId, null, null, "group-" + jobId));
        }

        publisher(client).publish(batch);

        assertThat(client.sendRequests()).as("ten distinct groups must not fragment batching").hasSize(1);
        assertThat(client.sendRequests().getFirst().entries()).hasSize(10);
    }
}

package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.shared.dispatch.DispatchQueueName;
import io.flowcatalyst.platform.shared.dispatch.QueuePriority;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.queue.sqs.SqsClients;
import io.flowcatalyst.router.queue.sqs.SqsQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/// The deployed [DispatchPublisher]: publishes each claimed job to its
/// client's SQS FIFO queue for its dispatch priority
/// (`docs/spec/deployed-dispatch.md` §3, ruling O2). A job's destination
/// queue is resolved the same way [io.flowcatalyst.platform.dispatch.RouterConfigDocumentBuilder]
/// composes the served document's queue names — [DispatchQueueName#compose] —
/// so a job's stamped destination and the router's merged config can never
/// disagree on the shape.
///
/// ### Resolving each job's destination (tenant, priority)
///
/// A claimed job (surfaced here as [PublishedMessage]) carries only
/// `clientId` / `subscriptionId`, never the tenant identifier or priority
/// themselves — the claim query
/// ([io.flowcatalyst.platform.dispatchjob.DispatchJobRepository#claimPending])
/// is deliberately join-free. [#destinationFor] delegates the whole
/// resolution to [DispatchDestinationResolver] — the SAME collaborator
/// [PostgresQueuePublisher] uses, so the two publishers cannot independently
/// drift on where a job goes. See that class's doc for the tenant (falling
/// back to [ClientIdentifier#RESERVED_PLATFORM], ruling R5) and priority
/// (ruling R6: [QueuePriority#DEFAULT] on anything unusable) rules.
///
/// ### Concurrency
///
/// Several dispatcher lanes ([DispatchLanes]) call [#publish] at once. Every
/// call is self-contained (its own nonce, its own failed-group set) and the
/// collaborators are thread-safe, so nothing here is shared between calls but
/// the [SqsClient]. A group's jobs always reach the same lane, so two calls
/// never carry the same group at the same time.
///
/// ### Chunking and per-queue grouping (ruling O2)
///
/// A dispatcher lane takes up to `LaneBatch` (100) jobs at a time; SQS caps
/// `SendMessageBatch` at 10, and one such call can only address a single queue. [#publish] first
/// partitions the claim-ordered batch into one ordered list per destination
/// queue — a single forward pass over `batch`, appending each job to its
/// destination's list — then builds each of those lists into chunks of at
/// most [#MAX_BATCH_SIZE], in order.
///
/// **Why this keeps claim order meaningful.** FIFO ordering is a per-queue,
/// per-`MessageGroupId` guarantee: two jobs bound for different queues (or
/// different priorities, which are different queues) have no ordering
/// relationship to preserve in the first place, so interleaving them in the
/// original claim order versus grouping them by destination is not a
/// behavioural difference — only *within* one destination's list does claim
/// order matter, and the single forward pass preserves it exactly, both
/// across the grouping step and across each list's own chunking.
///
/// ### No two jobs of the same group ever share a chunk — this is NOT merely
/// defensive
///
/// `claimPending` orders by `message_group, sequence, created_at, id`
/// (`docs/spec/deployed-dispatch.md` §3), so two jobs in the same message
/// group are ADJACENT in claim order and, absent this rule, would routinely
/// land in the same 10-entry chunk. SQS reports `SendMessageBatch` failures
/// **per entry**: if the earlier of the two fails and the later succeeds, the
/// later is durably `QUEUED` while the earlier reverts to `PENDING` and is
/// republished afterwards — the group is now delivered out of order. Per-group
/// ordering is load-bearing (`BLOCK_ON_ERROR`/`NEXT_ON_ERROR` depend on it),
/// so O2's whole premise — that a partial revert is safe because only
/// unpublished jobs move — is correct **only** if "unpublished" also accounts
/// for ordering, not merely for which ids got an error back from AWS.
/// [#publish] enforces two rules together to make that true:
///
///   1. **One job per group per chunk.** Each destination's jobs are built
///      into LAYERS — the i-th unsent job of every group, groups in order of
///      first appearance — and each layer is packed into chunks of up to
///      [#MAX_BATCH_SIZE]; layers are sent in order. A group's jobs therefore
///      go out in their original order, never two in one call, while calls
///      are filled from different groups (ten groups of ten jobs cost ten
///      full calls; closing a chunk at each repeat cost about ninety). A
///      group-less job's own job id is never shared by another job, so
///      group-less jobs all sit in the first layer and pack freely.
///   2. **A failed group poisons its own later jobs for the rest of THIS
///      publish call.** `failedGroups` — one `Set<String>` scoped to one
///      [#publish] invocation, spanning every destination and every chunk —
///      accumulates the group of every job SQS reports failed
///      (`response.failed()`) or that was in a chunk that threw. Before a
///      later job is ever placed into a chunk (so before it is ever sent),
///      its group is checked against this set; a poisoned job is added
///      straight to `unpublished` and never reaches SQS at all. Combined with
///      rule 1 (which guarantees a group's members arrive one-at-a-time, in
///      claim order, never two in the same send), this means a group is
///      either fully delivered in claim order, or truncated at its first
///      failure with every later member reverted alongside it — never
///      re-ordered.
///
/// ### FIFO identifiers (ruling R2)
///
/// `MessageGroupId` is the job's own message group
/// ([io.flowcatalyst.router.wire.Message#messageGroupId()]). A group-less job
/// still needs SOME group id — FIFO requires the field — and is given its
/// OWN job id as that group: since no two distinct jobs ever share a job id,
/// this imposes no ordering relationship between any two group-less jobs
/// (each becomes a singleton group of one), which is exactly the "no
/// ordering" property a group-less job is supposed to have.
///
/// `MessageDeduplicationId` is the job id plus a nonce generated fresh for
/// every [#publish] call (never reused across calls) — see [#dedupId] for why
/// this is the whole point of ruling R2, and never merely a formality.
///
/// ### Lazy queue creation (settled item 3)
///
/// A `SendMessageBatch` against a queue that does not exist yet
/// ([SqsQueue#isQueueMissing]) creates it (`FifoQueue=true`,
/// content-based dedup OFF — this publisher always supplies its own ids) and
/// retries the SAME request once, reusing the SAME dedup ids: the first
/// attempt never reached a queue that could have deduplicated anything, so
/// reusing them is correct, not merely convenient.
///
/// ### Partial failure (ruling O2)
///
/// A chunk's `SendMessageBatchResponse#failed()` entries, and a whole chunk
/// that throws (including a second [SqsException] after the lazy-create
/// retry, or any non-"queue missing" failure), are both reported as
/// unpublished — and, per the group-poisoning rule above, both also poison
/// their own job's group for the rest of this call. Every other queued chunk
/// (and every other group) is still attempted rather than aborting the whole
/// [#publish] call on the first bad chunk — a delivery problem for one
/// client's queue, or one message group, must not strand every other client's
/// (or every other group's) jobs in the same claimed batch.
public final class SqsDispatchPublisher implements DispatchPublisher, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SqsDispatchPublisher.class);

    /// SQS's hard cap on a single `SendMessageBatch` call — well below
    /// `SchedulerConfig#laneBatch` (100), which is exactly why [#publish]
    /// chunks (ruling O2).
    static final int MAX_BATCH_SIZE = 10;

    /// SQS's hard cap on `MessageDeduplicationId` — see [#dedupId].
    private static final int MAX_DEDUP_ID_LENGTH = 128;

    private final SqsClient client;
    private final DispatchQueueSettings settings;
    private final DispatchDestinationResolver destinations;

    /// Production entry point: builds its own [SqsClient] once, for
    /// `settings`' region, via the SDK's default credentials/region provider
    /// chain — the same idiom [SqsQueue] and
    /// [io.flowcatalyst.server.dbsecret.DbSecretFetcher] already use — and
    /// its own [PoolCodeResolver] / [SubscriptionPriorityCache] (through a
    /// fresh [DispatchDestinationResolver]), independent of any instance
    /// [DispatchScheduler] builds for the poller itself (deliberately
    /// duplicated rather than threaded across a new dependency edge between
    /// the two).
    public SqsDispatchPublisher(DataSource dataSource, DispatchQueueSettings settings) {
        this(dataSource, settings, 1);
    }

    /// As above, for a scheduler whose `concurrency` dispatcher lanes call
    /// [#publish] at once (each sends one chunk at a time): the client's
    /// connection pool is sized for that many calls in flight.
    public SqsDispatchPublisher(DataSource dataSource, DispatchQueueSettings settings, int concurrency) {
        this(buildClient(settings, concurrency), settings, new DispatchDestinationResolver(
                new PoolCodeResolver(dataSource), new SubscriptionPriorityCache(dataSource), settings));
    }

    /// Test seam: the resolver's own two collaborators, built into a fresh
    /// [DispatchDestinationResolver] — kept so existing tests need not change
    /// shape.
    SqsDispatchPublisher(SqsClient client, DispatchQueueSettings settings, PoolCodeResolver tenants,
                         SubscriptionPriorityCache priorities) {
        this(client, settings, new DispatchDestinationResolver(tenants, priorities, settings));
    }

    SqsDispatchPublisher(SqsClient client, DispatchQueueSettings settings, DispatchDestinationResolver destinations) {
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.destinations = Objects.requireNonNull(destinations, "destinations");
    }

    /// Longest one SQS attempt may take. There is no long poll on this path
    /// (every call is a `SendMessageBatch` of at most ten small entries), and a
    /// dispatcher lane — and the permits its jobs hold, which is what lets the
    /// poller claim more — waits on every call, so a stalled connection must be
    /// cut off quickly.
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    /// Longest a whole call may take including the SDK's retries.
    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(10);

    /// A lane sends its chunks one at a time, so each concurrent [#publish]
    /// needs one connection; the pool is never smaller than this.
    static final int MAX_HTTP_CONNECTIONS = 10;

    /// Package-visible so a test can read the configured timeouts.
    static SqsClientBuilder clientBuilder(DispatchQueueSettings settings) {
        return clientBuilder(settings, 1);
    }

    static SqsClientBuilder clientBuilder(DispatchQueueSettings settings, int concurrency) {
        var builder = SqsClients.builder(API_CALL_TIMEOUT, API_CALL_ATTEMPT_TIMEOUT,
                Math.max(MAX_HTTP_CONNECTIONS, concurrency));
        if (!settings.sqsRegion().isBlank()) {
            builder.region(Region.of(settings.sqsRegion()));
        }
        return builder;
    }

    private static SqsClient buildClient(DispatchQueueSettings settings, int concurrency) {
        return clientBuilder(settings, concurrency).build();
    }

    @Override
    public void publish(List<PublishedMessage> batch) throws PublishException {
        if (batch.isEmpty()) return;

        Map<DispatchQueueName, List<PublishedMessage>> byQueue = new LinkedHashMap<>();
        for (PublishedMessage m : batch) {
            byQueue.computeIfAbsent(destinationFor(m), k -> new ArrayList<>()).add(m);
        }

        String attemptNonce = UUID.randomUUID().toString();
        List<String> unpublished = new ArrayList<>();
        Throwable lastFailure = null;
        // Scoped to this ONE publish call and shared across every destination
        // and every chunk (never per-chunk) — see the class doc's "A failed
        // group poisons its own later jobs". A group id is a plain string
        // (the job's own message group, or a group-less job's own job id,
        // which nothing else can share), so one flat set is correct
        // regardless of how many destinations are involved.
        Set<String> failedGroups = new HashSet<>();

        for (var entry : byQueue.entrySet()) {
            DispatchQueueName queueName = entry.getKey();
            // Layers: the i-th job of every group, groups in order of first
            // appearance. A group's jobs are therefore sent in their claim
            // order and never two in one call (rule 1), while a call is
            // filled from DIFFERENT groups: ten groups of ten cost ten full
            // calls, where closing a chunk at each repeat cost about ninety.
            // Each layer is sent before the next is built, so a failure in
            // layer i poisons (rule 2) the group's jobs in layer i+1 before
            // they are ever placed in a chunk.
            for (List<PublishedMessage> layer : layers(entry.getValue())) {
                List<PublishedMessage> sendable = new ArrayList<>(layer.size());
                for (PublishedMessage candidate : layer) {
                    if (failedGroups.contains(groupIdFor(candidate))) {
                        // Never sent at all — see the class doc's rule 2. This
                        // keeps the group's relative order: it reverts to
                        // PENDING alongside the sibling that actually failed,
                        // rather than racing a later publish attempt against
                        // whatever a real send might have done.
                        unpublished.add(candidate.jobId());
                    } else {
                        sendable.add(candidate);
                    }
                }
                for (int from = 0; from < sendable.size(); from += MAX_BATCH_SIZE) {
                    List<PublishedMessage> chunk =
                            sendable.subList(from, Math.min(from + MAX_BATCH_SIZE, sendable.size()));
                    try {
                        List<String> failedIds = sendChunk(queueName, chunk, attemptNonce);
                        unpublished.addAll(failedIds);
                        if (!failedIds.isEmpty()) {
                            Set<String> failedIdSet = Set.copyOf(failedIds);
                            for (PublishedMessage m : chunk) {
                                if (failedIdSet.contains(m.jobId())) {
                                    failedGroups.add(groupIdFor(m));
                                }
                            }
                        }
                    } catch (RuntimeException e) {
                        lastFailure = e;
                        for (PublishedMessage m : chunk) {
                            unpublished.add(m.jobId());
                            failedGroups.add(groupIdFor(m));
                        }
                        LOG.atWarn().setMessage("sqs publish chunk failed; job(s) will revert to PENDING")
                                .addKeyValue("queue", queueName.value())
                                .addKeyValue("count", chunk.size())
                                .setCause(e)
                                .log();
                    }
                }
            }
        }

        if (!unpublished.isEmpty()) {
            throw new PublishException(
                    "sqs publish failed for " + unpublished.size() + " of " + batch.size() + " job(s)",
                    lastFailure, unpublished);
        }
    }

    /// Splits one destination's claim-ordered jobs into layers: layer `i` holds
    /// the `i`-th job of every group (groups in order of first appearance), so
    /// no layer has two jobs of one group and each group's jobs sit in
    /// successive layers in their original order. Group-less jobs are groups of
    /// one (their own job id) and all land in layer 0.
    private static List<List<PublishedMessage>> layers(List<PublishedMessage> jobs) {
        Map<String, List<PublishedMessage>> byGroup = new LinkedHashMap<>();
        for (PublishedMessage m : jobs) {
            byGroup.computeIfAbsent(groupIdFor(m), k -> new ArrayList<>()).add(m);
        }
        List<List<PublishedMessage>> layers = new ArrayList<>();
        for (List<PublishedMessage> group : byGroup.values()) {
            for (int i = 0; i < group.size(); i++) {
                if (layers.size() == i) layers.add(new ArrayList<>());
                layers.get(i).add(group.get(i));
            }
        }
        return layers;
    }

    private DispatchQueueName destinationFor(PublishedMessage m) {
        return destinations.destinationFor(m);
    }

    /// The `MessageGroupId` a job publishes under — see [#entryFor] and the
    /// class doc's "FIFO identifiers". Factored out so the chunk-building
    /// loop in [#publish] and the actual SQS entry always agree on what a
    /// job's group is.
    private static String groupIdFor(PublishedMessage m) {
        return m.message().messageGroupId() != null ? m.message().messageGroupId() : m.jobId();
    }

    /// Sends one chunk (at most [#MAX_BATCH_SIZE] entries, one destination
    /// queue), creating the queue and retrying once if it does not exist yet
    /// (settled item 3). Returns the job ids SQS itself reported as failed
    /// within the batch — a chunk-level exception propagates instead of
    /// returning, so the caller's per-chunk `catch` (not this method) is what
    /// marks a THROWN failure's whole chunk unpublished.
    private List<String> sendChunk(DispatchQueueName queueName, List<PublishedMessage> chunk, String attemptNonce) {
        String queueUrl = settings.queueUriFor(queueName);
        SendMessageBatchRequest request = buildRequest(queueUrl, chunk, attemptNonce);
        SendMessageBatchResponse response;
        try {
            response = client.sendMessageBatch(request);
        } catch (SqsException e) {
            if (!SqsQueue.isQueueMissing(e)) {
                throw e;
            }
            createQueueIfAbsent(queueName);
            // Same request, same dedup ids: the first attempt never reached a
            // queue that could have deduplicated anything, so this is still
            // the ONE publish attempt, not a second one (see the class doc's
            // "Lazy queue creation").
            response = client.sendMessageBatch(request);
        }
        return response.failed().stream().map(BatchResultErrorEntry::id).toList();
    }

    private void createQueueIfAbsent(DispatchQueueName queueName) {
        try {
            client.createQueue(CreateQueueRequest.builder()
                    .queueName(queueName.value())
                    .attributes(Map.of(
                            QueueAttributeName.FIFO_QUEUE, "true",
                            // This publisher always supplies its own MessageDeduplicationId
                            // (ruling R2) — content-based dedup would be redundant at best
                            // and, since it hashes the body alone, unable to see the
                            // per-attempt nonce that makes R2's guarantee work at worst.
                            QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                    .build());
        } catch (QueueNameExistsException racedAgainstAnotherCreator) {
            // Another instance (or another chunk of this same publish call,
            // for a different tenant sharing no queue — this exception is
            // per-queue-name) created it between our failed send and here;
            // the queue exists now, which is all this method promises.
        }
    }

    private SendMessageBatchRequest buildRequest(String queueUrl, List<PublishedMessage> chunk, String attemptNonce) {
        List<SendMessageBatchRequestEntry> entries = chunk.stream().map(m -> entryFor(m, attemptNonce)).toList();
        return SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(entries).build();
    }

    private SendMessageBatchRequestEntry entryFor(PublishedMessage m, String attemptNonce) {
        return SendMessageBatchRequestEntry.builder()
                .id(m.jobId())
                .messageBody(Json.write(m.message()))
                .messageGroupId(groupIdFor(m))
                .messageDeduplicationId(dedupId(m.jobId(), attemptNonce))
                .build();
    }

    /// **Ruling R2 — the whole point of this method.** Never the bare job id:
    /// SQS FIFO deduplicates identical `MessageDeduplicationId`s sent within a
    /// 5-minute window, and the reaper can revert an abandoned `PROCESSING`
    /// job back to `PENDING`, which the ordinary claim/publish path
    /// then republishes — a SECOND, genuinely-intended delivery. If that
    /// republish reused the job id as its dedup id, SQS would silently drop
    /// it as a duplicate of the first (never-delivered, or already-consumed)
    /// attempt, stranding the job forever with no error anywhere. `attemptNonce`
    /// is generated fresh once per [#publish] call (see the call site), so the
    /// SAME job published on two different calls always gets two different
    /// dedup ids; the lazy-create retry inside one [#sendChunk] deliberately
    /// reuses the same id (see that method) because it is still one attempt.
    ///
    /// This does NOT reopen ledger R-18: the platform's `status`/
    /// `scheduled_for` machinery is still the only thing this codebase relies
    /// on for dedup. The broker-native id exists solely because FIFO demands
    /// the field, and is deliberately unique-per-attempt so the broker can
    /// never actually deduplicate anything — see [DispatchPublisher]'s class
    /// doc.
    private static String dedupId(String jobId, String attemptNonce) {
        String id = jobId + ":" + attemptNonce;
        // Defensive only: a TSID job id (~13 chars) plus a UUID nonce (36
        // chars) never approaches SQS's 128-character limit, but a future job
        // id scheme must not silently produce an invalid request.
        return id.length() <= MAX_DEDUP_ID_LENGTH ? id : id.substring(0, MAX_DEDUP_ID_LENGTH);
    }

    /// Closes the underlying [SqsClient] (its HTTP connection pool and
    /// threads) — `Server` closes this alongside `DispatchScheduler` on
    /// shutdown, mirroring [SqsQueue#close()]'s own note that a consumer does
    /// not assume sole ownership of its client; this publisher, by contrast,
    /// always owns the client it was built or constructed with, so closing it
    /// here is unconditional.
    @Override
    public void close() {
        client.close();
    }
}

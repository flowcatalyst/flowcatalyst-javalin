package io.flowcatalyst.platform.scheduler;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// A hand-written stub of the two [SqsClient] methods [SqsDispatchPublisher]
/// calls — `sendMessageBatch` and `createQueue` — a materially different
/// responsibility from the consumption side's own
/// [io.flowcatalyst.router.queue.sqs.SqsQueue]'s
/// `receiveMessage`/`deleteMessage`/`getQueueAttributes` fake in a different
/// package, so this is a focused fake written next to the publisher rather
/// than widening that one (CONVENTIONS §7 forbids a mocking library either
/// way; there is no SQS in the test environment).
final class FakeSqsSendClient implements SqsClient {

    private final List<SendMessageBatchRequest> sendRequests = new ArrayList<>();
    private final List<CreateQueueRequest> createQueueRequests = new ArrayList<>();

    /// Queue URLs that behave as "does not exist yet": every send against one
    /// throws [QueueDoesNotExistException] until [#createQueue] is called for
    /// the matching queue NAME, which removes it — the same lazy-creation
    /// transition real SQS exhibits.
    private final Set<String> missingQueueUrls = new HashSet<>();

    /// job id -> the AWS error code to report for that one entry on the NEXT
    /// send that includes it (consumed once on use) — scripts a per-entry
    /// partial failure inside an otherwise successful batch.
    private final Map<String, String> failEntryOnce = new HashMap<>();

    /// job ids that, if present ANYWHERE in a `SendMessageBatch` request,
    /// make the WHOLE call throw once (consumed on use) — scripts a
    /// chunk-level failure (a thrown exception, not a per-entry
    /// `BatchResultErrorEntry`) distinct from [#failEntryOnce].
    private final Set<String> failWholeSendContaining = new HashSet<>();

    boolean closed;

    void markQueueMissing(String queueUrl) {
        missingQueueUrls.add(queueUrl);
    }

    void failEntryOnce(String jobId, String errorCode) {
        failEntryOnce.put(jobId, errorCode);
    }

    void failWholeSendContainingJobOnce(String jobId) {
        failWholeSendContaining.add(jobId);
    }

    List<SendMessageBatchRequest> sendRequests() {
        return List.copyOf(sendRequests);
    }

    List<CreateQueueRequest> createQueueRequests() {
        return List.copyOf(createQueueRequests);
    }

    @Override
    public SendMessageBatchResponse sendMessageBatch(SendMessageBatchRequest request) {
        sendRequests.add(request);
        for (SendMessageBatchRequestEntry entry : request.entries()) {
            if (failWholeSendContaining.remove(entry.id())) {
                throw new RuntimeException("fake: whole-batch send failure containing job " + entry.id());
            }
        }
        if (missingQueueUrls.contains(request.queueUrl())) {
            throw QueueDoesNotExistException.builder().message("fake: queue does not exist").build();
        }
        List<SendMessageBatchResultEntry> successful = new ArrayList<>();
        List<BatchResultErrorEntry> failed = new ArrayList<>();
        for (SendMessageBatchRequestEntry entry : request.entries()) {
            String errorCode = failEntryOnce.remove(entry.id());
            if (errorCode != null) {
                failed.add(BatchResultErrorEntry.builder().id(entry.id()).code(errorCode).senderFault(Boolean.FALSE).build());
            } else {
                successful.add(SendMessageBatchResultEntry.builder().id(entry.id()).messageId(entry.id() + "-msg").build());
            }
        }
        return SendMessageBatchResponse.builder().successful(successful).failed(failed).build();
    }

    @Override
    public CreateQueueResponse createQueue(CreateQueueRequest request) {
        createQueueRequests.add(request);
        missingQueueUrls.removeIf(url -> url.endsWith("/" + request.queueName()));
        return CreateQueueResponse.builder().queueUrl("https://sqs.fake/000000000000/" + request.queueName()).build();
    }

    @Override
    public String serviceName() {
        return "sqs";
    }

    @Override
    public void close() {
        closed = true;
    }
}

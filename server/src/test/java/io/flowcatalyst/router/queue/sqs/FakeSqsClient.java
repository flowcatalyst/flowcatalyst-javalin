package io.flowcatalyst.router.queue.sqs;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/// A hand-written stub of the handful of [SqsClient] methods [SqsQueue]
/// calls — there is no SQS in the test environment, and CONVENTIONS §7
/// forbids adding a mocking library. Every other [SqsClient] method keeps
/// its SDK default (which throws), so a test that exercises one by mistake
/// fails loudly rather than silently no-opping.
final class FakeSqsClient implements SqsClient {

    private final Deque<ReceiveMessageResponse> receiveResponses = new ArrayDeque<>();
    private final List<ReceiveMessageRequest> receiveRequests = new ArrayList<>();
    private final List<DeleteMessageRequest> deleteRequests = new ArrayList<>();
    private final List<GetQueueAttributesRequest> attributesRequests = new ArrayList<>();
    private final List<ChangeMessageVisibilityRequest> changeVisibilityRequests = new ArrayList<>();

    private Supplier<RuntimeException> receiveError;
    private RuntimeException deleteError;
    private Map<QueueAttributeName, String> attributes;
    private Supplier<RuntimeException> attributesError;
    private RuntimeException changeVisibilityError;

    void enqueueReceive(ReceiveMessageResponse response) {
        receiveResponses.addLast(response);
    }

    void failNextReceiveWith(Supplier<RuntimeException> error) {
        this.receiveError = error;
    }

    void failDeleteWith(RuntimeException error) {
        this.deleteError = error;
    }

    void failChangeVisibilityWith(RuntimeException error) {
        this.changeVisibilityError = error;
    }

    void queueAttributes(Map<QueueAttributeName, String> attributes) {
        this.attributes = attributes;
    }

    /// Every call to `getQueueAttributes` (not just the next one) fails with
    /// a fresh instance from `error` — the existence-check tests recheck
    /// repeatedly and each attempt needs its own exception, not one thrown
    /// once and then a `null` NPE on the second call.
    void failAttributesWith(Supplier<RuntimeException> error) {
        this.attributesError = error;
    }

    void failAttributesWith(RuntimeException error) {
        this.attributesError = () -> error;
    }

    /// Undoes [#failAttributesWith] — the existence-check tests that watch a
    /// queue transition from missing to present need this rather than a
    /// second [FakeSqsClient].
    void clearAttributesError() {
        this.attributesError = null;
    }

    List<ReceiveMessageRequest> receiveRequests() {
        return receiveRequests;
    }

    List<DeleteMessageRequest> deleteRequests() {
        return deleteRequests;
    }

    List<ChangeMessageVisibilityRequest> changeVisibilityRequests() {
        return changeVisibilityRequests;
    }

    List<GetQueueAttributesRequest> attributesRequests() {
        return attributesRequests;
    }

    @Override
    public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
        receiveRequests.add(request);
        if (receiveError != null) {
            RuntimeException error = receiveError.get();
            receiveError = null;
            throw error;
        }
        ReceiveMessageResponse next = receiveResponses.pollFirst();
        return next != null ? next : ReceiveMessageResponse.builder().messages(List.of()).build();
    }

    @Override
    public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
        deleteRequests.add(request);
        if (deleteError != null) {
            throw deleteError;
        }
        return DeleteMessageResponse.builder().build();
    }

    @Override
    public ChangeMessageVisibilityResponse changeMessageVisibility(ChangeMessageVisibilityRequest request) {
        changeVisibilityRequests.add(request);
        if (changeVisibilityError != null) {
            throw changeVisibilityError;
        }
        return ChangeMessageVisibilityResponse.builder().build();
    }

    @Override
    public GetQueueAttributesResponse getQueueAttributes(GetQueueAttributesRequest request) {
        attributesRequests.add(request);
        if (attributesError != null) {
            throw attributesError.get();
        }
        return GetQueueAttributesResponse.builder()
                .attributes(attributes == null ? Map.of() : attributes)
                .build();
    }

    @Override
    public String serviceName() {
        return "sqs";
    }

    boolean closed;

    @Override
    public void close() {
        closed = true;
    }
}

package io.flowcatalyst.router.queue.sqs;

import software.amazon.awssdk.services.sqs.SqsClient;
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

    private Supplier<RuntimeException> receiveError;
    private RuntimeException deleteError;
    private Map<QueueAttributeName, String> attributes;
    private RuntimeException attributesError;

    void enqueueReceive(ReceiveMessageResponse response) {
        receiveResponses.addLast(response);
    }

    void failNextReceiveWith(Supplier<RuntimeException> error) {
        this.receiveError = error;
    }

    void failDeleteWith(RuntimeException error) {
        this.deleteError = error;
    }

    void queueAttributes(Map<QueueAttributeName, String> attributes) {
        this.attributes = attributes;
    }

    void failAttributesWith(RuntimeException error) {
        this.attributesError = error;
    }

    List<ReceiveMessageRequest> receiveRequests() {
        return receiveRequests;
    }

    List<DeleteMessageRequest> deleteRequests() {
        return deleteRequests;
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
    public GetQueueAttributesResponse getQueueAttributes(GetQueueAttributesRequest request) {
        if (attributesError != null) {
            throw attributesError;
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

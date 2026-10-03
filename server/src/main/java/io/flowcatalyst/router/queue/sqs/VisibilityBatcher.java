package io.flowcatalyst.router.queue.sqs;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/// Sends deferrals and nacks (`ChangeMessageVisibility`) as `ChangeMessageVisibilityBatch`
/// calls of up to ten, without making the caller wait.
///
/// A deferral is best-effort and the caller (a poll loop deferring a whole batch
/// that found its pool full) has nothing to do with the answer, so [#submit] only
/// queues. The queue is bounded: when the broker cannot keep up the caller blocks,
/// which is the back-pressure that stops a poll loop deferring faster than SQS
/// accepts. As with [DeleteBatcher] there is no fill window and a few drainers
/// share the queue; they exit when idle and are started again by the next submit.
final class VisibilityBatcher {

    static final int MAX_BATCH = 10;
    static final int DRAINERS = 4;
    static final int CAPACITY = 4096;
    static final Duration IDLE_EXIT = Duration.ofSeconds(30);

    /// One visibility change. `settled` runs once the broker has answered or the call
    /// failed — the caller's counter, which counts a deferral either way.
    record Change(String receiptHandle, int visibilitySeconds, String messageId, Runnable settled) {
    }

    private final SqsClient client;
    private final String queueUrl;
    private final String identifier;
    private final BiConsumer<String, Throwable> failure;
    private final LinkedBlockingQueue<Change> waiting = new LinkedBlockingQueue<>(CAPACITY);
    private final AtomicInteger live = new AtomicInteger();

    VisibilityBatcher(SqsClient client, String queueUrl, String identifier, BiConsumer<String, Throwable> failure) {
        this.client = client;
        this.queueUrl = queueUrl;
        this.identifier = identifier;
        this.failure = failure;
    }

    /// Queues the change; blocks only when [#CAPACITY] changes are already waiting.
    void submit(Change change) throws InterruptedException {
        waiting.put(change);
        if (live.get() == 0) {
            startDrainers();
        }
    }

    private synchronized void startDrainers() {
        if (live.get() != 0) {
            return;
        }
        live.set(DRAINERS);
        for (int i = 0; i < DRAINERS; i++) {
            Thread.ofVirtual().name("sqs-visibility-" + identifier + "-" + i).start(this::drain);
        }
    }

    private void drain() {
        List<Change> batch = new ArrayList<>(MAX_BATCH);
        int idleTicks = 0;
        while (true) {
            Change first;
            try {
                first = waiting.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (first == null) {
                if (++idleTicks >= IDLE_EXIT.toSeconds()) {
                    break;
                }
                continue;
            }
            idleTicks = 0;
            batch.clear();
            batch.add(first);
            waiting.drainTo(batch, MAX_BATCH - 1);
            send(batch);
        }
        if (live.decrementAndGet() == 0 && !waiting.isEmpty()) {
            startDrainers();
        }
    }

    private void send(List<Change> batch) {
        try {
            List<ChangeMessageVisibilityBatchRequestEntry> entries = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                entries.add(ChangeMessageVisibilityBatchRequestEntry.builder()
                        .id(Integer.toString(i))
                        .receiptHandle(batch.get(i).receiptHandle())
                        .visibilityTimeout(batch.get(i).visibilitySeconds())
                        .build());
            }
            ChangeMessageVisibilityBatchResponse response = client.changeMessageVisibilityBatch(
                    ChangeMessageVisibilityBatchRequest.builder().queueUrl(queueUrl).entries(entries).build());
            for (BatchResultErrorEntry f : response.failed()) {
                Change c = batch.get(Integer.parseInt(f.id()));
                failure.accept("sqs ChangeMessageVisibilityBatch entry failed for message " + c.messageId() + ": "
                        + f.code() + " " + f.message(), null);
            }
        } catch (RuntimeException e) {
            failure.accept("sqs ChangeMessageVisibilityBatch failed", e);
        } finally {
            for (Change c : batch) {
                c.settled().run();
            }
        }
    }
}

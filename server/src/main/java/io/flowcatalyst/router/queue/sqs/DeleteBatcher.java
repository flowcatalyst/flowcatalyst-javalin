package io.flowcatalyst.router.queue.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// Coalesces concurrent per-message deletes for one queue into
/// `DeleteMessageBatch` calls of up to [#MAX_BATCH] entries.
///
/// There is no fill window: a drainer takes whatever is already waiting (up to
/// ten) and sends it, so an idle queue still deletes a lone message with no
/// added latency and a busy one sends ten per request. Each caller still blocks
/// until the broker has answered for its own receipt, so [SqsQueue#ack] keeps
/// its meaning: true only when the delete succeeded.
///
/// A few drainers run per queue so one slow round trip does not cap the queue's
/// ack rate at ten messages per round trip.
final class DeleteBatcher {

    private static final Logger log = LoggerFactory.getLogger(DeleteBatcher.class);

    static final int MAX_BATCH = 10;
    static final int DRAINERS = 4;

    private record Pending(String receiptHandle, CompletableFuture<Void> done) {
    }

    private final SqsClient client;
    private final String queueUrl;
    private final String identifier;
    private final LinkedBlockingQueue<Pending> waiting = new LinkedBlockingQueue<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopped;

    DeleteBatcher(SqsClient client, String queueUrl, String identifier) {
        this.client = client;
        this.queueUrl = queueUrl;
        this.identifier = identifier;
    }

    /// Blocks until the broker has answered for this receipt. Throws on failure.
    void delete(String receiptHandle) {
        if (stopped) {
            throw new IllegalStateException("sqs delete batcher closed: " + identifier);
        }
        start();
        Pending p = new Pending(receiptHandle, new CompletableFuture<>());
        waiting.add(p);
        try {
            p.done.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    void close() {
        stopped = true;
    }

    private void start() {
        if (started.compareAndSet(false, true)) {
            for (int i = 0; i < DRAINERS; i++) {
                Thread.ofVirtual().name("sqs-delete-" + identifier + "-" + i).start(this::drain);
            }
        }
    }

    private void drain() {
        List<Pending> batch = new ArrayList<>(MAX_BATCH);
        while (!stopped) {
            Pending first;
            try {
                first = waiting.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (first == null) {
                continue;
            }
            batch.clear();
            batch.add(first);
            waiting.drainTo(batch, MAX_BATCH - 1);
            send(batch);
        }
        Pending p;
        while ((p = waiting.poll()) != null) {
            p.done.completeExceptionally(new IllegalStateException("sqs delete batcher closed: " + identifier));
        }
    }

    private void send(List<Pending> batch) {
        try {
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                entries.add(DeleteMessageBatchRequestEntry.builder()
                        .id(Integer.toString(i))
                        .receiptHandle(batch.get(i).receiptHandle)
                        .build());
            }
            DeleteMessageBatchResponse response = client.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build());
            Map<Integer, BatchResultErrorEntry> failed = new ConcurrentHashMap<>();
            for (BatchResultErrorEntry f : response.failed()) {
                failed.put(Integer.parseInt(f.id()), f);
            }
            for (int i = 0; i < batch.size(); i++) {
                BatchResultErrorEntry f = failed.get(i);
                if (f == null) {
                    batch.get(i).done.complete(null);
                } else {
                    batch.get(i).done.completeExceptionally(new IllegalStateException(
                            "DeleteMessageBatch entry failed: " + f.code() + " " + f.message()));
                }
            }
        } catch (RuntimeException e) {
            log.debug("sqs DeleteMessageBatch failed for queue {}: {}", identifier, e.toString());
            for (Pending p : batch) {
                p.done.completeExceptionally(e);
            }
        }
    }
}

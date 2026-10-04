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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/// Coalesces concurrent per-message deletes for one queue into
/// `DeleteMessageBatch` calls of up to [#MAX_BATCH] entries.
///
/// One primary drainer per queue takes the first waiting delete and then lingers
/// for up to the linger cap (measured from that first item) for more, sending as
/// soon as it holds [#MAX_BATCH] (a full batch never waits) or as soon as any
/// collected delete is urgent (an ordered message's ack: the router will not
/// deliver the next message of the group until it returns). Several drainers sharing the queue would steal each
/// other's items and send small batches, so there is deliberately one. Under load
/// (a full batch already waiting after an enqueue) up to [#MAX_HELPERS] helper
/// drainers start; a helper drains whatever is immediately available with no
/// linger and exits when the queue is empty, so a slow round trip does not cap the
/// queue's ack rate at ten per round trip.
///
/// Each caller still blocks until the broker has answered for its own receipt, so
/// [SqsQueue#ack] keeps its meaning: true only when the delete succeeded. The
/// added latency of a non-urgent ack is bounded by the linger cap plus the SDK
/// call; an urgent one is not held at all.
final class DeleteBatcher {

    private static final Logger log = LoggerFactory.getLogger(DeleteBatcher.class);

    static final int MAX_BATCH = 10;
    static final int MAX_HELPERS = 3;
    static final long DEFAULT_LINGER_NANOS = TimeUnit.SECONDS.toNanos(5);
    /// How often a lingering primary looks at [#stopped], so close() does not wait out the cap.
    private static final long STOP_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    static final java.time.Duration IDLE_EXIT = java.time.Duration.ofSeconds(30);

    /// One waiting delete: the caller's thread parks on it until a drainer sets the
    /// outcome and unparks it. Cheaper than a future per ack — one object, no
    /// completion stack — and the callers are virtual threads, for which park is cheap.
    private static final class Pending {
        final String receiptHandle;
        final boolean urgent;
        final Thread caller;
        /// 0 = waiting, 1 = deleted, 2 = failed (see [#error]). Written last, so a reader
        /// that sees it non-zero also sees [#error].
        volatile int state;
        RuntimeException error;

        Pending(String receiptHandle, boolean urgent, Thread caller) {
            this.receiptHandle = receiptHandle;
            this.urgent = urgent;
            this.caller = caller;
        }

        /// Completion is first-wins, as with a future: a whole-call failure after some
        /// entries were already answered must not overturn them.
        void succeed() {
            if (state != 0) {
                return;
            }
            state = 1;
            LockSupport.unpark(caller);
        }

        void fail(RuntimeException e) {
            if (state != 0) {
                return;
            }
            error = e;
            state = 2;
            LockSupport.unpark(caller);
        }
    }

    private final SqsClient client;
    private final String queueUrl;
    private final String identifier;
    private final LinkedBlockingQueue<Pending> waiting = new LinkedBlockingQueue<>();
    /// Primary drainers alive (0 or 1). It exits after [#IDLE_EXIT] with nothing to do
    /// and is started again by the next delete, so a closed queue's batcher goes away
    /// on its own while acks of already-polled messages keep working after close
    /// (the [io.flowcatalyst.router.queue.Consumer] contract).
    private final AtomicInteger live = new AtomicInteger();
    private final AtomicInteger helpers = new AtomicInteger();
    private volatile boolean stopped;
    /// The longest the primary waits for a batch to fill, from its first item. Tests set it.
    volatile long lingerNanos = DEFAULT_LINGER_NANOS;

    DeleteBatcher(SqsClient client, String queueUrl, String identifier) {
        this(client, queueUrl, identifier, DEFAULT_LINGER_NANOS);
    }

    DeleteBatcher(SqsClient client, String queueUrl, String identifier, long lingerNanos) {
        this.lingerNanos = lingerNanos;
        this.client = client;
        this.queueUrl = queueUrl;
        this.identifier = identifier;
    }

    /// Blocks until the broker has answered for this receipt. Throws on failure.
    void delete(String receiptHandle) {
        delete(receiptHandle, false);
    }

    /// `urgent` makes the primary send what it has collected at once instead of lingering.
    void delete(String receiptHandle, boolean urgent) {
        if (stopped) {
            throw new IllegalStateException("sqs delete batcher closed: " + identifier);
        }
        startPrimary();
        Pending p = new Pending(receiptHandle, urgent, Thread.currentThread());
        waiting.add(p);
        if (waiting.size() >= MAX_BATCH) {
            startHelper();
        }
        // Like CompletableFuture#join: an interrupt does not abandon the wait (the
        // delete is already queued and will happen), it is re-asserted on return.
        boolean interrupted = false;
        while (p.state == 0) {
            LockSupport.park(p);
            if (Thread.interrupted()) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (p.state == 2) {
            throw p.error;
        }
    }

    void close() {
        stopped = true;
    }

    /// Helper drainers currently running (for tests).
    int helpersRunning() {
        return helpers.get();
    }

    private void startPrimary() {
        if (live.get() == 0 && live.compareAndSet(0, 1)) {
            Thread.ofVirtual().name("sqs-delete-" + identifier).start(this::drain);
        }
    }

    private void startHelper() {
        while (true) {
            int h = helpers.get();
            if (h >= MAX_HELPERS) {
                return;
            }
            if (helpers.compareAndSet(h, h + 1)) {
                try {
                    Thread.ofVirtual().name("sqs-delete-" + identifier + "-h" + h).start(this::help);
                } catch (Throwable t) {
                    helpers.decrementAndGet();
                    throw t;
                }
                return;
            }
        }
    }

    private void help() {
        try {
            List<Pending> batch = new ArrayList<>(MAX_BATCH);
            while (!stopped) {
                batch.clear();
                if (waiting.drainTo(batch, MAX_BATCH) == 0) {
                    break;
                }
                send(batch);
            }
        } finally {
            helpers.decrementAndGet();
        }
    }

    private void drain() {
        List<Pending> batch = new ArrayList<>(MAX_BATCH);
        int idleTicks = 0;
        while (!stopped) {
            Pending first;
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
            long deadline = System.nanoTime() + lingerNanos;
            boolean cut = first.urgent;
            try {
                while (!cut && !stopped) {
                    int before = batch.size();
                    waiting.drainTo(batch, MAX_BATCH - before);
                    cut = anyUrgent(batch, before);
                    if (cut || batch.size() >= MAX_BATCH) {
                        break;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    // Any arrival (urgent or not) wakes this poll, so an urgent delete is seen at once.
                    Pending next = waiting.poll(Math.min(remaining, STOP_CHECK_NANOS), TimeUnit.NANOSECONDS);
                    if (next != null) {
                        batch.add(next);
                        cut = next.urgent;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(batch);
                break;
            }
            send(batch);
        }
        if (stopped) {
            Pending p;
            while ((p = waiting.poll()) != null) {
                p.fail(new IllegalStateException("sqs delete batcher closed: " + identifier));
            }
        }
        live.set(0);
        // Restart if a delete slipped in while we were leaving.
        if (!waiting.isEmpty() && !stopped) {
            startPrimary();
        }
    }

    private static boolean anyUrgent(List<Pending> batch, int from) {
        for (int i = from; i < batch.size(); i++) {
            if (batch.get(i).urgent) {
                return true;
            }
        }
        return false;
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
                    batch.get(i).succeed();
                } else {
                    batch.get(i).fail(new IllegalStateException(
                            "DeleteMessageBatch entry failed: " + f.code() + " " + f.message()));
                }
            }
        } catch (RuntimeException e) {
            log.debug("sqs DeleteMessageBatch failed for queue {}: {}", identifier, e.toString());
            for (Pending p : batch) {
                p.fail(e);
            }
        }
    }
}

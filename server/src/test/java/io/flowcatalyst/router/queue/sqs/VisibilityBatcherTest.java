package io.flowcatalyst.router.queue.sqs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityBatcherTest {

    private final FakeSqsClient client = new FakeSqsClient();
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final AtomicInteger settled = new AtomicInteger();
    private final VisibilityBatcher batcher =
            new VisibilityBatcher(client, "https://sqs/q", "q", (m, c) -> failures.add(m));

    private VisibilityBatcher.Change change(int i) {
        return new VisibilityBatcher.Change("r-" + i, 10 + i, "m-" + i, settled::incrementAndGet);
    }

    @Test
    @DisplayName("submit returns without waiting for the broker, and the change is sent and counted afterwards")
    void submitDoesNotWait() throws Exception {
        var gate = new CountDownLatch(1);
        client.gateVisibilityBatches(gate);

        batcher.submit(change(1)); // returns although the call is blocked on the gate
        assertThat(settled.get()).isZero();

        gate.countDown();
        awaitTrue(() -> settled.get() == 1);
        var sent = client.visibilityBatches().get(0).entries();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).receiptHandle()).isEqualTo("r-1");
        assertThat(sent.get(0).visibilityTimeout()).isEqualTo(11);
    }

    @Test
    @DisplayName("queued changes coalesce into batches of at most ten, each with its own timeout")
    void coalesceIntoBatchesOfTen() throws Exception {
        var gate = new CountDownLatch(1);
        client.gateVisibilityBatches(gate);
        int n = 200;
        for (int i = 0; i < n; i++) {
            batcher.submit(change(i));
        }
        Thread.sleep(200);
        gate.countDown();
        awaitTrue(() -> settled.get() == n);

        var batches = List.copyOf(client.visibilityBatches());
        assertThat(batches).allSatisfy(b -> assertThat(b.entries().size()).isBetween(1, 10));
        assertThat(batches.stream().mapToInt(b -> b.entries().size()).sum()).isEqualTo(n);
        assertThat(batches.size()).isLessThan(n / 2);
        assertThat(batches.stream().flatMap(b -> b.entries().stream())
                .filter(e -> e.receiptHandle().equals("r-7")).findFirst().orElseThrow().visibilityTimeout())
                .isEqualTo(17);
    }

    @Test
    @DisplayName("a failed call is reported once and every change in it is still settled")
    void wholeCallFailureIsReportedAndSettled() throws Exception {
        client.failVisibilityBatchWith(SdkClientException.create("boom"));

        batcher.submit(change(1));
        awaitTrue(() -> settled.get() == 1);

        assertThat(failures).hasSize(1).first().asString().contains("ChangeMessageVisibilityBatch failed");
    }

    @Test
    @DisplayName("when the broker cannot keep up submit blocks instead of queueing without bound")
    void submitBlocksWhenTheQueueIsFull() throws Exception {
        var gate = new CountDownLatch(1);
        client.gateVisibilityBatches(gate);
        // Four drainers each hold up to ten in a blocked call; CAPACITY more fit in the queue.
        int fits = VisibilityBatcher.CAPACITY + VisibilityBatcher.DRAINERS * VisibilityBatcher.MAX_BATCH;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < fits; i++) {
                batcher.submit(change(i));
                if (i == VisibilityBatcher.DRAINERS * VisibilityBatcher.MAX_BATCH) {
                    Thread.sleep(200); // let the drainers take their ten each
                }
            }
            Future<?> extra = pool.submit(() -> {
                batcher.submit(change(-1));
                return null;
            });
            Thread.sleep(300);
            assertThat(extra.isDone()).as("the call after the queue is full must wait").isFalse();

            gate.countDown();
            extra.get(10, TimeUnit.SECONDS);
        }
        awaitTrue(() -> settled.get() == fits + 1);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(5);
        }
    }
}

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
        // How many fit is not exact: each of the drainers holds between one and ten changes in its
        // blocked call, depending on what was queued when it woke. So do not assume a number: submit
        // from another thread until it stops making progress, and check where it stopped.
        var submitted = new AtomicInteger();
        int attempts = VisibilityBatcher.CAPACITY + VisibilityBatcher.DRAINERS * VisibilityBatcher.MAX_BATCH + 50;
        Thread producer = Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < attempts; i++) {
                    batcher.submit(change(i));
                    submitted.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            // Wait until the producer has been stuck at the same count for a while.
            int last = -1;
            long stableSince = System.nanoTime();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                int now = submitted.get();
                if (now != last) {
                    last = now;
                    stableSince = System.nanoTime();
                } else if (System.nanoTime() - stableSince > TimeUnit.MILLISECONDS.toNanos(400)) {
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(producer.isAlive()).as("the producer must be blocked in submit, not finished").isTrue();
            assertThat(submitted.get())
                    .as("bounded: the queue holds CAPACITY, plus at most ten in each drainer's blocked call")
                    .isGreaterThanOrEqualTo(VisibilityBatcher.CAPACITY)
                    .isLessThanOrEqualTo(VisibilityBatcher.CAPACITY
                            + VisibilityBatcher.DRAINERS * VisibilityBatcher.MAX_BATCH);
        } finally {
            gate.countDown();
        }
        producer.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(producer.isAlive()).as("once the broker answers, the blocked submit completes").isFalse();
        awaitTrue(() -> settled.get() == attempts);
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

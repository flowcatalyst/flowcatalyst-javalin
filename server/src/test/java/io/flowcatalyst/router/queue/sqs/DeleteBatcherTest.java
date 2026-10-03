package io.flowcatalyst.router.queue.sqs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeleteBatcherTest {

    private final FakeSqsClient client = new FakeSqsClient();
    private final DeleteBatcher batcher = new DeleteBatcher(client, "https://sqs/q", "q");

    @Test
    @DisplayName("a lone delete is sent immediately as a batch of one")
    void loneDeleteIsNotDelayed() {
        batcher.delete("r-1");

        assertThat(client.batchRequests()).hasSize(1);
        assertThat(client.batchRequests().get(0).entries()).hasSize(1);
        batcher.close();
    }

    @Test
    @DisplayName("concurrent deletes coalesce: no batch exceeds 10 and there are fewer calls than deletes")
    void concurrentDeletesCoalesce() throws Exception {
        int n = 200;
        var gate = new CountDownLatch(1);
        client.gateBatches(gate);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String handle = "r-" + i;
                futures.add(pool.submit(() -> batcher.delete(handle)));
            }
            // Four drainers are blocked in the gated call; everything else queues behind them.
            awaitTrue(() -> client.batchesEntered() == DeleteBatcher.DRAINERS);
            Thread.sleep(200);
            gate.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }

        List<DeleteMessageBatchRequest> batches = List.copyOf(client.batchRequests());
        assertThat(batches).allSatisfy(b -> assertThat(b.entries().size()).isBetween(1, 10));
        assertThat(batches.stream().mapToInt(b -> b.entries().size()).sum()).isEqualTo(n);
        assertThat(batches.size()).isLessThan(n / 2);
        batcher.close();
    }

    @Test
    @DisplayName("a failed entry fails only its own delete")
    void perEntryFailureFailsOnlyThatDelete() throws Exception {
        client.failBatchEntriesWithHandlePrefix("bad");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> good = pool.submit(() -> batcher.delete("good-1"));
            Future<?> bad = pool.submit(() -> batcher.delete("bad-1"));
            good.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> bad.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("DeleteMessageBatch entry failed: InvalidParameterValue nope");
        }
        batcher.close();
    }

    @Test
    @DisplayName("a failed call fails every delete in it, with the call's own exception")
    void wholeCallFailureFailsEveryDelete() {
        var boom = SdkClientException.create("boom");
        client.failBatchWith(boom);

        assertThatThrownBy(() -> batcher.delete("r-1")).isSameAs(boom);
        batcher.close();
    }

    @Test
    @DisplayName("close fails deletes still waiting instead of leaving them blocked")
    void closeFailsWaitingDeletes() throws Exception {
        var gate = new CountDownLatch(1);
        client.gateBatches(gate);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> inFlight = new ArrayList<>();
            for (int i = 0; i < DeleteBatcher.DRAINERS; i++) {
                String h = "inflight-" + i;
                inFlight.add(pool.submit(() -> batcher.delete(h)));
                // One at a time so each drainer takes exactly one.
                int want = i + 1;
                awaitTrue(() -> client.batchesEntered() == want);
            }
            Future<?> waiting = pool.submit(() -> batcher.delete("waiting"));
            Thread.sleep(200);

            batcher.close();
            gate.countDown();

            assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            for (Future<?> f : inFlight) {
                f.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("a delete after close fails immediately")
    void deleteAfterCloseFails() {
        batcher.close();
        assertThatThrownBy(() -> batcher.delete("r-1")).isInstanceOf(IllegalStateException.class);
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

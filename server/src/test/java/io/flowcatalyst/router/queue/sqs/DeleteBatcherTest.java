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
    private final DeleteBatcher batcher = new DeleteBatcher(client, "https://sqs/q", "q", TimeUnit.MILLISECONDS.toNanos(1));

    @Test
    @DisplayName("a lone delete waits for the linger cap, then goes as a batch of one")
    void loneDeleteWaitsForLinger() {
        batcher.lingerNanos = TimeUnit.MILLISECONDS.toNanos(30);
        long start = System.nanoTime();
        batcher.delete("r-1");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(client.batchRequests()).hasSize(1);
        assertThat(client.batchRequests().get(0).entries()).hasSize(1);
        assertThat(elapsedMs).isBetween(25L, 100L);
        batcher.close();
    }

    @Test
    @DisplayName("a full batch is sent at once, without waiting for the linger cap")
    void fullBatchIsNotDelayed() throws Exception {
        batcher.lingerNanos = TimeUnit.SECONDS.toNanos(20);
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < DeleteBatcher.MAX_BATCH; i++) {
                String handle = "r-" + i;
                futures.add(pool.submit(() -> batcher.delete(handle)));
            }
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(5000);
        assertThat(client.batchRequests()).hasSize(1);
        assertThat(client.batchRequests().get(0).entries()).hasSize(DeleteBatcher.MAX_BATCH);
        batcher.close();
    }

    @Test
    @DisplayName("an urgent delete cuts the batch at once with what was collected")
    void urgentDeleteCutsTheBatch() throws Exception {
        batcher.lingerNanos = TimeUnit.SECONDS.toNanos(20);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> lingering = pool.submit(() -> batcher.delete("slow"));
            Thread.sleep(200);
            assertThat(client.batchRequests()).isEmpty();
            Future<?> urgent = pool.submit(() -> batcher.delete("fast", true));
            urgent.get(5, TimeUnit.SECONDS);
            lingering.get(5, TimeUnit.SECONDS);
        }
        assertThat(client.batchRequests()).hasSize(1);
        assertThat(client.batchRequests().get(0).entries()).hasSize(2);
        batcher.close();
    }

    @Test
    @DisplayName("a lone urgent delete is sent immediately")
    void loneUrgentDeleteIsImmediate() throws Exception {
        batcher.lingerNanos = TimeUnit.SECONDS.toNanos(20);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> batcher.delete("fast", true)).get(5, TimeUnit.SECONDS);
        }
        assertThat(client.batchRequests()).hasSize(1);
        batcher.close();
    }

    @Test
    @DisplayName("close ends a lingering primary promptly instead of waiting out the cap")
    void closeEndsLinger() throws Exception {
        batcher.lingerNanos = TimeUnit.SECONDS.toNanos(20);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> lingering = pool.submit(() -> batcher.delete("slow"));
            Thread.sleep(200);
            batcher.close();
            lingering.get(5, TimeUnit.SECONDS);
        }
        assertThat(client.batchRequests()).hasSize(1);
    }

    @Test
    @DisplayName("steady moderate load fills batches: average >= 8 and far fewer calls than deletes")
    void steadyLoadFillsBatches() throws Exception {
        batcher.lingerNanos = TimeUnit.MILLISECONDS.toNanos(5);
        int n = 2000;
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                String handle = "r-" + i;
                futures.add(pool.submit(() -> batcher.delete(handle)));
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        }
        List<DeleteMessageBatchRequest> batches = List.copyOf(client.batchRequests());
        int total = batches.stream().mapToInt(b -> b.entries().size()).sum();
        assertThat(total).isEqualTo(n);
        assertThat(batches).allSatisfy(b -> assertThat(b.entries().size()).isBetween(1, 10));
        assertThat((double) total / batches.size()).isGreaterThanOrEqualTo(8.0);
        assertThat(batches.size()).isLessThan(n / 5);
        batcher.close();
    }

    @Test
    @DisplayName("a burst engages helpers (but never more than the cap) and no batch exceeds 10")
    void burstEngagesHelpers() throws Exception {
        int n = 500;
        var gate = new CountDownLatch(1);
        client.gateBatches(gate);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String handle = "r-" + i;
                futures.add(pool.submit(() -> batcher.delete(handle)));
            }
            try {
                awaitTrue(() -> client.batchesEntered() >= 2);
                Thread.sleep(300);
                assertThat(client.maxConcurrentBatches()).isBetween(2, 1 + DeleteBatcher.MAX_HELPERS);
            } finally {
                gate.countDown();
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        }

        List<DeleteMessageBatchRequest> batches = List.copyOf(client.batchRequests());
        assertThat(batches).allSatisfy(b -> assertThat(b.entries().size()).isBetween(1, 10));
        assertThat(batches.stream().mapToInt(b -> b.entries().size()).sum()).isEqualTo(n);
        assertThat(batches.size()).isLessThan(n / 2);
        awaitTrue(() -> batcher.helpersRunning() == 0);
        batcher.close();
    }

    @Test
    @DisplayName("helpers exit when idle and start again on the next burst")
    void helpersExitWhenIdle() throws Exception {
        for (int round = 0; round < 2; round++) {
            var gate = new CountDownLatch(1);
            client.gateBatches(gate);
            int before = client.batchesEntered();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    String handle = "r-" + round + "-" + i;
                    futures.add(pool.submit(() -> batcher.delete(handle)));
                }
                try {
                    awaitTrue(() -> batcher.helpersRunning() > 0);
                    awaitTrue(() -> client.batchesEntered() - before >= 2);
                } finally {
                    gate.countDown();
                }
                for (Future<?> f : futures) {
                    f.get(20, TimeUnit.SECONDS);
                }
            }
            awaitTrue(() -> batcher.helpersRunning() == 0);
        }
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
            Future<?> inFlight = pool.submit(() -> batcher.delete("inflight"));
            Future<?> waiting;
            try {
                awaitTrue(() -> client.batchesEntered() == 1);
                waiting = pool.submit(() -> batcher.delete("waiting"));
                Thread.sleep(200);
                batcher.close();
            } finally {
                gate.countDown();
            }

            assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            inFlight.get(10, TimeUnit.SECONDS);
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

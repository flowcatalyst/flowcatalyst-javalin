package io.flowcatalyst.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// Request-level admission: FIFO, bounded per pool, groups isolated, NO_DB unbounded.
///
/// [RequestWorkers] has no production caller today — it was written for the
/// Vert.x listener's dispatch model B, which the owner reverted 2026-09-08
/// (`docs/vertx-plan.md` closing section) back to Javalin/Jetty, whose own
/// thread-per-request model does not go through this class. The class and
/// its FIFO/bounded/group-isolation contract are kept exactly as before —
/// pinned here directly rather than through a live HTTP listener, since none
/// wires it any more.
class RequestWorkersTest {

    @Test
    void oneWorkerRunsQueuedTasksInSubmissionOrder() throws Exception {
        try (var w = RequestWorkers.of(1, Map.of())) {
            var gate = new CountDownLatch(1);
            var order = new CopyOnWriteArrayList<Integer>();
            var done = new CountDownLatch(4);
            w.submit(null, () -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add(0);
                done.countDown();
            });
            for (int i = 1; i <= 3; i++) {
                int n = i;
                w.submit(null, () -> {
                    order.add(n);
                    done.countDown();
                });
            }
            Thread.sleep(100);
            assertThat(w.queued(null)).isEqualTo(3);
            gate.countDown();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(0, 1, 2, 3);
        }
    }

    @Test
    void aPoolRunsAtMostItsSizeConcurrently() throws Exception {
        try (var w = RequestWorkers.of(2, Map.of())) {
            var release = new CountDownLatch(1);
            var started = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) {
                w.submit(null, () -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(started.await(300, TimeUnit.MILLISECONDS)).as("the third must wait for a worker").isFalse();
            assertThat(w.busy(null)).isEqualTo(2);
            assertThat(w.queued(null)).isEqualTo(1);
            release.countDown();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aBlockedDispatchPoolDoesNotBlockTheMainPoolAndNoDbIsUnbounded() throws Exception {
        try (var w = RequestWorkers.of(1, Map.of(Group.DISPATCH, 1))) {
            var release = new CountDownLatch(1);
            Runnable block = () -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            w.submit(Group.DISPATCH, block);
            w.submit(null, block);
            Thread.sleep(100);
            assertThat(w.busy(Group.DISPATCH)).isEqualTo(1);
            assertThat(w.busy(null)).isEqualTo(1);
            var ran = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) w.submit(Group.NO_DB, ran::countDown);
            assertThat(ran.await(1, TimeUnit.SECONDS)).as("NO_DB never waits for a worker").isTrue();
            assertThat(w.queued(null)).isZero();
            release.countDown();
        }
    }
}

package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.pool.Broker;
import io.flowcatalyst.router.pool.Mediator;
import io.flowcatalyst.router.pool.Pool;
import io.flowcatalyst.router.pool.PoolMetrics;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.wire.MediationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// The shutdown sequence (`docs/spec/router.md` §11).
class RouterShutdownTest {

    private final Clock clock = Clock.systemUTC();
    private final InFlightTracker tracker = new InFlightTracker(clock);
    /// Real elapsed time with a tiny budget. A frozen fake clock would make
    /// the drain loop wait forever, since its deadline could never arrive —
    /// which is exactly what happened the first time this was written.
    private RouterShutdown shutdown(Duration drainTimeout) {
        return new RouterShutdown(tracker, drainTimeout);
    }

    @Test
    @DisplayName("an idle router shuts down immediately and reports a clean drain")
    void idleShutdownIsClean() {
        var result = shutdown(Duration.ofSeconds(60)).shutdown(List.of(), List.of(), List.of());

        assertThat(result.drained()).isTrue();
        assertThat(result.redeliveries()).isZero();
    }

    @Test
    @DisplayName("sources are stopped first, so the in-flight set can only shrink")
    void sourcesAreStoppedFirst() {
        // Draining against a queue still handing out work finishes only when
        // the timeout says so. Stopping the sources first is what gives the
        // drain a definite end.
        var consumer = new FakeConsumer("q://1");
        var loop = new CountingThread();

        shutdown(Duration.ofSeconds(60)).shutdown(List.of(loop), List.of(consumer), List.of());

        assertThat(loop.interrupted).isTrue();
        assertThat(consumer.closed).isTrue();
    }

    @Test
    @DisplayName("a drain that cannot finish times out and reports the redeliveries")
    void drainTimesOutAndReports() {
        // Nothing releases these entries, so the drain can only expire.
        IntStream.range(0, 3).forEach(i -> tracker.register(inFlight("m" + i)));

        var result = shutdown(Duration.ofMillis(600)).shutdown(List.of(), List.of(), List.of());

        assertThat(result.drained()).isFalse();
        assertThat(result.inFlightAtStart()).isEqualTo(3);
        // Each of these will reach its target a second time — the
        // at-least-once guarantee being spent, and worth surfacing as such.
        assertThat(result.redeliveries()).isEqualTo(3);
    }

    @Test
    @DisplayName("the drain ends as soon as the last message finishes, not when the timeout expires")
    void drainEndsEarlyWhenWorkFinishes() throws Exception {
        tracker.register(inFlight("m1"));
        var released = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            sleepQuietly(Duration.ofMillis(200));
            tracker.remove("m1");
            released.countDown();
        });

        var result = new RouterShutdown(tracker, Duration.ofSeconds(30))
                .shutdown(List.of(), List.of(), List.of());

        assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.drained()).isTrue();
        assertThat(result.redeliveries()).isZero();
    }

    @Test
    @DisplayName("pools are stopped after the drain, not before")
    void poolsStopAfterTheDrain() {
        // Stopping them first would nack messages out from under workers
        // about to succeed — an unnecessary redelivery is a duplicate
        // somebody has to absorb.
        var nacked = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var pool = new Pool(new Pool.Config("A", 2, 0),
                (message, recordFailure) -> MediationOutcome.Success.of(200),
                new Broker() {
                    @Override
                    public void ack(QueuedMessage message) {
                    }

                    @Override
                    public void nack(QueuedMessage message, Duration delay) {
                        nacked.add(message.id());
                    }
                },
                PoolMetrics.NO_OP, clock);

        var result = shutdown(Duration.ofSeconds(60)).shutdown(List.of(), List.of(), List.of(pool));

        assertThat(result.drained()).isTrue();
        assertThat(nacked).as("nothing buffered, so nothing handed back").isEmpty();
        pool.close();
    }

    @Test
    @DisplayName("one uncooperative consumer does not stop the others closing")
    void oneBadConsumerDoesNotBlockTheRest() {
        var throwing = new FakeConsumer("bad") {
            @Override
            public void close() {
                super.close();
                throw new IllegalStateException("broker gone");
            }
        };
        var healthy = new FakeConsumer("good");

        shutdown(Duration.ofSeconds(60)).shutdown(List.of(), List.of(throwing, healthy), List.of());

        assertThat(healthy.closed).isTrue();
    }

    @Test
    @DisplayName("nothing is acked on the way out")
    void nothingIsAckedOnExit() {
        // Everything returns to the broker. Acking on shutdown would be
        // claiming success for work that may not have happened.
        var consumer = new FakeConsumer("q://1");
        tracker.register(inFlight("m1"));

        shutdown(Duration.ofMillis(100)).shutdown(List.of(), List.of(consumer), List.of());

        assertThat(consumer.acked).isEmpty();
    }

    private InFlightMessage inFlight(String id) {
        var now = clock.instant();
        return new InFlightMessage(id, "b-" + id, "", "q://1", now, now, "", "1", "r-" + id, 0);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CountingThread extends Thread {
        volatile boolean interrupted;

        @Override
        public void interrupt() {
            interrupted = true;
            super.interrupt();
        }
    }

    private static class FakeConsumer implements Consumer {
        private final String id;
        volatile boolean closed;
        final List<String> acked = new java.util.concurrent.CopyOnWriteArrayList<>();

        FakeConsumer(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) {
            return PollResult.empty();
        }

        @Override
        public void ack(QueuedMessage message) {
            acked.add(message.id());
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public Optional<QueueMetrics> metrics() {
            return Optional.empty();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}

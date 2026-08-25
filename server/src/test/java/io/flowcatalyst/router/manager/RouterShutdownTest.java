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

        @Override
        public void release(QueuedMessage message) {
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
        public boolean ack(QueuedMessage message) {
            acked.add(message.id());
            return true;
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

    @Test
    @DisplayName("one hung consumer does not spend the whole budget while healthy ones wait")
    void slowCloseDoesNotBlockTheOthers() throws Exception {
        // Sequentially, eight consumers each taking a second is eight
        // seconds. Concurrently it is one — and, more importantly, a single
        // wedged broker costs the timeout once rather than once per queue.
        var slow = new java.util.ArrayList<Consumer>();
        var closedCount = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 8; i++) {
            slow.add(new FakeConsumer("q-" + i) {
                @Override
                public void close() {
                    sleepQuietly(Duration.ofMillis(400));
                    super.close();
                    closedCount.incrementAndGet();
                }
            });
        }

        long startedAt = System.nanoTime();
        shutdown(Duration.ofSeconds(60)).shutdown(List.of(), slow, List.of());
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(closedCount.get()).as("every consumer still closes").isEqualTo(8);
        assertThat(elapsed).as("closed at once, not one after another").isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a consumer that never returns from close cannot hold the process open")
    void wedgedCloseIsBounded() {
        // Best-effort: shutdown gives up on it and carries on, because a
        // broker that will not answer must not prevent the process exiting.
        var wedged = new FakeConsumer("wedged") {
            @Override
            public void close() {
                sleepQuietly(Duration.ofMinutes(5));
            }
        };
        var healthy = new FakeConsumer("healthy");

        var bounded = new RouterShutdown(tracker, Duration.ofMillis(100), Duration.ofMillis(300));

        long startedAt = System.nanoTime();
        bounded.shutdown(List.of(), List.of(wedged, healthy), List.of());
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(healthy.closed).as("a healthy consumer still closes").isTrue();
        assertThat(elapsed).as("the wedged one is abandoned, not waited on")
                .isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a pool hands back everything it holds, however much that is")
    void poolHandsBackAllBufferedMessages() {
        // Hundreds of serial broker round-trips inside a shutdown budget was
        // the reason to make this concurrent; the count is what must not
        // change.
        var nacked = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
        var pool = new Pool(new Pool.Config("A", 1, 0),
                (message, recordFailure) -> {
                    sleepQuietly(Duration.ofMinutes(1)); // never completes
                    return MediationOutcome.Success.of(200);
                },
                new Broker() {
                    @Override
                    public void ack(QueuedMessage message) {
                    }

                    @Override
                    public void nack(QueuedMessage message, Duration delay) {
                        nacked.put(message.id(), true);
                    }

        @Override
        public void release(QueuedMessage message) {
        }
                },
                PoolMetrics.NO_OP, clock);

        IntStream.range(0, 200).forEach(i -> pool.submit(ordered("g", "m" + i)));
        awaitBuffered(pool, 150);

        pool.stop();

        assertThat(nacked).as("nothing buffered is silently dropped").hasSizeGreaterThanOrEqualTo(150);
        pool.close();
    }

    private static void awaitBuffered(Pool pool, int atLeast) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (pool.queueSize() < atLeast && System.nanoTime() < deadline) {
            sleepQuietly(Duration.ofMillis(5));
        }
    }

    private static QueuedMessage ordered(String group, String id) {
        return QueuedMessage.of(
                new io.flowcatalyst.router.wire.Message(id, "", null, null,
                        io.flowcatalyst.router.wire.MediationType.HTTP, "https://x.test/h", group, false,
                        io.flowcatalyst.router.wire.DispatchMode.BLOCK_ON_ERROR),
                "b-" + id, "r-" + id, "q://1");
    }

}

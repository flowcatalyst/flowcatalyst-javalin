package io.flowcatalyst.router.lifecycle;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.Warnings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// The periodic housekeeping (`docs/spec/router.md` §4.6, constants 33, 35).
class LifecycleLoopsTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final RecordingWarnings warnings = new RecordingWarnings();
    private LifecycleLoops loops;

    @AfterEach
    void stop() {
        if (loops != null) {
            loops.close();
        }
    }

    // ── The reaper ──────────────────────────────────────────────────────

    @Test
    @DisplayName("an entry nothing has touched is reaped")
    void idleEntryIsReaped() {
        track("m1");
        clock.advance(LifecycleLoops.REAP_MAX_AGE.plusMinutes(1));

        LifecycleLoops.reap(tracker, warnings);

        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("a retrying entry survives the reaper however long it takes")
    void retryingEntrySurvives() {
        // It is slow on purpose; reaping it would let a duplicate through
        // while the original is still being worked.
        track("m1");
        tracker.markRetrying("m1");
        clock.advance(Duration.ofHours(4));

        LifecycleLoops.reap(tracker, warnings);

        assertThat(tracker.size()).isOne();
    }

    @Test
    @DisplayName("a reap that finds nothing says nothing")
    void quietReapIsSilent() {
        track("m1");

        LifecycleLoops.reap(tracker, warnings);

        assertThat(warnings.raised).isEmpty();
        assertThat(tracker.size()).isOne();
    }

    @Test
    @DisplayName("a tracker past the threshold raises a resource warning")
    void largeTrackerWarns() {
        // Growth past this is usually a broker redelivering faster than
        // deliveries complete, and it ends in memory pressure.
        IntStream.range(0, LifecycleLoops.IN_FLIGHT_WARN_THRESHOLD).forEach(i -> track("m" + i));

        LifecycleLoops.reap(tracker, warnings);

        assertThat(warnings.raised).singleElement().asString()
                .contains("ERROR").contains("RESOURCE").contains("10000");
    }

    @Test
    @DisplayName("a tracker below the threshold does not warn")
    void smallTrackerIsQuiet() {
        IntStream.range(0, 50).forEach(i -> track("m" + i));

        LifecycleLoops.reap(tracker, warnings);

        assertThat(warnings.raised).isEmpty();
    }

    // ── The scheduler ───────────────────────────────────────────────────

    @Test
    @DisplayName("every task runs on its own cadence")
    void tasksRunPeriodically() {
        var fast = new AtomicInteger();
        var slow = new AtomicInteger();
        loops = new LifecycleLoops();

        loops.start(List.of(
                new LifecycleLoops.Task("fast", Duration.ofMillis(20), fast::incrementAndGet),
                new LifecycleLoops.Task("slow", Duration.ofMillis(200), slow::incrementAndGet)));

        await(() -> fast.get() >= 5);
        assertThat(fast.get()).as("the faster task runs more often").isGreaterThan(slow.get());
    }

    @Test
    @DisplayName("a task that throws does not stop its own loop")
    void throwingTaskKeepsRunning() {
        // One bad tick must not silence housekeeping for the life of the
        // process — that is the quiet degradation these loops exist to catch.
        var runs = new AtomicInteger();
        loops = new LifecycleLoops();

        loops.start(List.of(new LifecycleLoops.Task("angry", Duration.ofMillis(20), () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("every time");
        })));

        await(() -> runs.get() >= 5);
    }

    @Test
    @DisplayName("a task that throws does not stop the other tasks")
    void throwingTaskDoesNotAffectOthers() {
        var healthy = new AtomicInteger();
        loops = new LifecycleLoops();

        loops.start(List.of(
                new LifecycleLoops.Task("angry", Duration.ofMillis(20), () -> {
                    throw new IllegalStateException("every time");
                }),
                new LifecycleLoops.Task("healthy", Duration.ofMillis(20), healthy::incrementAndGet)));

        await(() -> healthy.get() >= 5);
    }

    @Test
    @DisplayName("closing stops every loop")
    void closeStopsEverything() {
        var runs = new AtomicInteger();
        loops = new LifecycleLoops();
        loops.start(List.of(new LifecycleLoops.Task("counter", Duration.ofMillis(20), runs::incrementAndGet)));
        await(() -> runs.get() >= 3);

        loops.close();
        int atClose = runs.get();
        sleep(Duration.ofMillis(200));

        assertThat(runs.get()).as("no ticks after close").isLessThanOrEqualTo(atClose + 1);
    }

    private void track(String id) {
        var now = clock.instant();
        tracker.register(new InFlightMessage(id, "b-" + id, "", "q://1", now, now, "", "1", "r-" + id, 0));
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(5));
        }
        throw new AssertionError("condition not met within 10s");
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class RecordingWarnings implements Warnings {
        final List<String> raised = new CopyOnWriteArrayList<>();

        @Override
        public void raise(Severity severity, String category, String message) {
            raised.add(severity + " " + category + " " + message);
        }
    }

    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

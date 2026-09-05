package io.flowcatalyst.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// [Projector] (stream spec §2): the four-tier pacing precedence
/// ([#nextSleep], reflectively invoked since it is package-private — the Go
/// counterpart of `TestNextSleep_AdaptiveTiers`), the leader gate, the
/// `Health#running` flag, and cancellation-by-interruption. No database: a
/// fake [Projector.Step] and short, test-only durations stand in for the
/// real batch size/pacing/clock.
class ProjectorTest {

    private static final ProjectorConfig CONFIG = new ProjectorConfig(true, 3,
            Duration.ofMillis(5), Duration.ofMillis(15), Duration.ofMillis(20));

    // ── nextSleep: the four tiers, in precedence order ──────────────────────

    @ParameterizedTest(name = "errored={0} n={1} -> {2}")
    @DisplayName("nextSleep: error beats empty-batch beats full-batch beats steady-state")
    @CsvSource({
            "true,  0, 20",  // error wins even over an empty batch
            "true,  5, 20",  // error wins even over a full/over batch
            "false, 0, 15",  // empty batch -> idle sleep
            "false, 3, 0",   // n == batchSize -> poll again immediately
            "false, 9, 0",   // n > batchSize -> poll again immediately
            "false, 1, 5",   // 0 < n < batchSize -> steady-state poll interval
    })
    void nextSleepPrecedence(boolean errored, int n, long expectedMillis) throws Exception {
        assertThat(invokeNextSleep(errored, n)).isEqualTo(Duration.ofMillis(expectedMillis));
    }

    private static Duration invokeNextSleep(boolean errored, int n) throws Exception {
        Method m = Projector.class.getDeclaredMethod("nextSleep", ProjectorConfig.class, boolean.class, int.class);
        m.setAccessible(true);
        return (Duration) m.invoke(null, CONFIG, errored, n);
    }

    // ── loop behaviour ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a disabled projector never runs the step and never sets Health#running")
    void disabledNeverRuns() {
        AtomicInteger calls = new AtomicInteger();
        Health health = new Health("disabled-test");
        var projector = new Projector("disabled-test", disabled(), n -> calls.incrementAndGet(), () -> true, health);

        projector.run();

        assertThat(calls.get()).isZero();
        assertThat(health.isRunning()).isFalse();
    }

    private static ProjectorConfig disabled() {
        return new ProjectorConfig(false, 3, Duration.ofMillis(5), Duration.ofMillis(15), Duration.ofMillis(20));
    }

    @Test
    @DisplayName("a non-leader loop never calls step, even while it keeps running")
    void nonLeaderNeverSteps() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        Health health = new Health("non-leader-test");
        var projector = new Projector("non-leader-test", CONFIG, n -> calls.incrementAndGet(), () -> false, health);

        Thread thread = Thread.ofVirtual().start(projector);
        // Several idle-sleep tiers' worth of time: if the leader gate were
        // broken this would already show a non-zero call count.
        Thread.sleep(80);
        assertThat(calls.get()).as("non-leader must never invoke the step").isZero();
        assertThat(health.isRunning()).isTrue();

        thread.interrupt();
        thread.join(1000);
        assertThat(thread.isAlive()).as("interrupting must stop the loop").isFalse();
        assertThat(health.isRunning()).as("running flag clears on exit").isFalse();
    }

    @Test
    @DisplayName("Health#running is set on entry and cleared on exit; a leader's loop does step")
    void leaderRunsAndFlagsHealth() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        Health health = new Health("leader-test");
        var projector = new Projector("leader-test", CONFIG, n -> {
            calls.incrementAndGet();
            return 0; // idle tier every time — keeps this deterministic and fast
        }, () -> true, health);

        assertThat(health.isRunning()).isFalse();
        Thread thread = Thread.ofVirtual().start(projector);
        Thread.sleep(50);
        assertThat(health.isRunning()).isTrue();
        assertThat(calls.get()).as("the leader's loop must actually call step").isGreaterThan(0);

        thread.interrupt();
        thread.join(1000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(health.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a step error is counted and paced with errorSleep, without stopping the loop")
    void stepErrorIsCountedAndPaced() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        Health health = new Health("error-test");
        var projector = new Projector("error-test", CONFIG, n -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        }, () -> true, health);

        Thread thread = Thread.ofVirtual().start(projector);
        Thread.sleep(90); // a couple of errorSleep (20ms) tiers
        thread.interrupt();
        thread.join(1000);

        assertThat(calls.get()).as("the loop keeps calling step after an error").isGreaterThan(1);
        assertThat(health.errorCount()).as("every failing call is counted").isEqualTo(calls.get());
        assertThat(health.batchSequence()).as("a thrown step never counts as processed").isZero();
    }

    @Test
    @DisplayName("shutdown: interrupting stops the loop within one sleep tier")
    void shutdownStopsWithinOneSleepTier() throws InterruptedException {
        Health health = new Health("shutdown-test");
        // A generous idle sleep so we can prove the interrupt cuts it short
        // rather than the loop merely finishing on its own between polls.
        ProjectorConfig slowIdle = new ProjectorConfig(true, 3, Duration.ofMillis(5), Duration.ofSeconds(5),
                Duration.ofMillis(20));
        var projector = new Projector("shutdown-test", slowIdle, n -> 0, () -> true, health);

        Thread thread = Thread.ofVirtual().start(projector);
        Thread.sleep(30); // let it enter the 5s idle sleep
        long start = System.nanoTime();
        thread.interrupt();
        thread.join(2000);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(thread.isAlive()).isFalse();
        assertThat(elapsedMs).as("interrupt must cut the sleep short, not wait it out").isLessThan(1000);
        assertThat(health.isRunning()).isFalse();
    }
}

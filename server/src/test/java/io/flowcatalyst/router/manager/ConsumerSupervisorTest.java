package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// Stall detection and consumer restart (`docs/spec/router.md` §4.4).
///
/// The restart delay is real time, so these use a supervisor with a short one
/// where the delay is not what is being asserted.
class ConsumerSupervisorTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final RecordingWarnings warnings = new RecordingWarnings();
    /// A negligible restart delay: these assert escalation and bookkeeping,
    /// not that the pause happens — that is a constant, not behaviour.
    private final ConsumerSupervisor supervisor =
            new ConsumerSupervisor(warnings, clock, Duration.ofMillis(1));

    private final QueueConfig config = QueueConfig.of("q://1");

    @Test
    @DisplayName("a heartbeat within the threshold is healthy")
    void healthyLoopIsNotStalled() {
        assertThat(supervisor.stalled(polledSecondsAgo(59))).isFalse();
    }

    @Test
    @DisplayName("a heartbeat older than the threshold is stalled")
    void silentLoopIsStalled() {
        assertThat(supervisor.stalled(polledSecondsAgo(61))).isTrue();
    }

    @Test
    @DisplayName("the threshold boundary is exclusive, so exactly 60s is still healthy")
    void thresholdBoundaryIsExclusive() {
        assertThat(supervisor.stalled(polledSecondsAgo(60))).isFalse();
    }

    @Test
    @DisplayName("a loop that has never polled is not stalled")
    void coldStartIsNotAStall() {
        // It may simply not have started yet. Treating a cold start as a
        // stall would restart every consumer moments after boot.
        assertThat(supervisor.stalled(Optional.<Instant>empty())).isFalse();
    }

    @Test
    @DisplayName("a restart warns and builds a replacement, WITHOUT closing the old consumer (R-26)")
    void restartReplacesTheConsumer() throws Exception {
        var stalled = new FakeConsumer("q://1");
        var built = new CopyOnWriteArrayList<FakeConsumer>();

        var replacement = supervisor.restart("q://1", config, stalled, queue -> {
            var fresh = new FakeConsumer(queue.queueName());
            built.add(fresh);
            return Optional.of(fresh);
        });

        assertThat(replacement).isPresent();
        // R-26: the old consumer is left alone here. Closing it would abort
        // whatever in-flight delivery it is still holding; the caller
        // (RouterServer) hands it to RouterManager#replaceConsumer, which
        // detaches it to the lingering set instead.
        assertThat(stalled.closed).isFalse();
        assertThat(built).hasSize(1);
        assertThat(warnings.raised).singleElement().asString()
                .contains("CONSUMER_HEALTH").contains("attempt 1");
    }

    @Test
    @DisplayName("repeated restarts escalate to critical")
    void repeatedRestartsEscalate() throws Exception {
        // Repeated restarts are the platform failing to fix itself, and at
        // some point that is not a warning any more.
        for (int i = 0; i < ConsumerSupervisor.CRITICAL_AFTER_ATTEMPTS + 1; i++) {
            supervisor.restart("q://1", config, new FakeConsumer("q://1"),
                    queue -> Optional.of(new FakeConsumer(queue.queueName())));
        }

        assertThat(warnings.raised).hasSize(ConsumerSupervisor.CRITICAL_AFTER_ATTEMPTS + 1);
        assertThat(warnings.raised.subList(0, ConsumerSupervisor.CRITICAL_AFTER_ATTEMPTS))
                .allSatisfy(raised -> assertThat(raised).startsWith("WARNING"));
        assertThat(warnings.raised.getLast()).startsWith("CRITICAL");
    }

    @Test
    @DisplayName("a failed rebuild counts as an attempt, so a hopeless consumer still escalates")
    void failedRebuildCountsAndEscalates() throws Exception {
        // Q28 ruled: this is the case Go got backwards. A consumer that can
        // never be rebuilt — bad credentials, deleted queue, wrong URI —
        // would otherwise warn at WARNING forever and never reach CRITICAL,
        // leaving the failure mode that most needs a human the quietest.
        IntStream.range(0, ConsumerSupervisor.CRITICAL_AFTER_ATTEMPTS + 1).forEach(i -> restartFailing());

        assertThat(supervisor.restartAttempts("q://1"))
                .isEqualTo(ConsumerSupervisor.CRITICAL_AFTER_ATTEMPTS + 1);
        assertThat(warnings.raised.getLast()).startsWith("CRITICAL");
    }

    @Test
    @DisplayName("the warning says whether it rebuilt or could not, because the causes differ")
    void warningDistinguishesTheOutcome() throws Exception {
        // A rebuild that keeps succeeding points at broker or network health;
        // one that cannot rebuild at all points at configuration.
        supervisor.restart("q://1", config, new FakeConsumer("q://1"),
                queue -> Optional.of(new FakeConsumer(queue.queueName())));
        restartFailing();

        assertThat(warnings.raised.getFirst()).contains("has been rebuilt");
        assertThat(warnings.raised.getLast()).contains("cannot be rebuilt");
    }

    private void restartFailing() {
        try {
            supervisor.restart("q://1", config, new FakeConsumer("q://1"), queue -> Optional.empty());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("recovery clears the count, so the next stall starts from zero")
    void recoveryClearsTheCount() throws Exception {
        supervisor.restart("q://1", config, new FakeConsumer("q://1"),
                queue -> Optional.of(new FakeConsumer(queue.queueName())));
        assertThat(supervisor.restartAttempts("q://1")).isOne();

        supervisor.recovered("q://1");

        assertThat(supervisor.restartAttempts("q://1")).isZero();
    }

    @Test
    @DisplayName("queues escalate independently of one another")
    void queuesAreIndependent() throws Exception {
        supervisor.restart("q://1", config, new FakeConsumer("q://1"),
                queue -> Optional.of(new FakeConsumer(queue.queueName())));

        assertThat(supervisor.restartAttempts("q://1")).isOne();
        assertThat(supervisor.restartAttempts("q://2")).isZero();
    }

    private Optional<Instant> polledSecondsAgo(long seconds) {
        return Optional.of(clock.instant().minusSeconds(seconds));
    }

    private static final class RecordingWarnings implements Warnings {
        final List<String> raised = new CopyOnWriteArrayList<>();

        @Override
        public void raise(Severity severity, String category, String message) {
            raised.add(severity + " " + category + " " + message);
        }
    }

    private static final class FakeConsumer implements Consumer {
        private final String id;
        volatile boolean closed;

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

    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
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

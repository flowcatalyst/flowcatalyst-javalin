package io.flowcatalyst.router.lifecycle;

import io.flowcatalyst.router.inflight.InFlightMessage;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Acknowledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` §4.6.
class StallDetectorTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InFlightTracker tracker = new InFlightTracker(clock);
    private final RecordingWarnings warnings = new RecordingWarnings();
    private final FakeQueue queue = new FakeQueue("q://1");

    private StallDetector detector(StallDetector.Config config) {
        return new StallDetector(tracker, warnings, id -> queue.identifier().equals(id) ? queue : null,
                config, clock);
    }

    @Test
    @DisplayName("a message owned less than the threshold is not stalled")
    void freshMessageIsNotStalled() {
        track("m1");
        clock.advance(Duration.ofSeconds(299));

        var sweep = detector(StallDetector.Config.REPORT_ONLY).sweep();

        assertThat(sweep.stalled()).isEmpty();
        assertThat(warnings.raised).isEmpty();
    }

    @Test
    @DisplayName("a message owned past the threshold is reported")
    void stalledMessageIsReported() {
        track("m1");
        clock.advance(Duration.ofSeconds(300));

        var sweep = detector(StallDetector.Config.REPORT_ONLY).sweep();

        assertThat(sweep.stalled()).singleElement()
                .extracting(InFlightMessage::messageId).isEqualTo("m1");
        assertThat(warnings.raised).singleElement().asString()
                .contains("STALL").contains("m1").contains("300s");
    }

    @Test
    @DisplayName("a retrying message is never stalled, however long it takes")
    void retryingMessageIsExcluded() {
        // attempts > 0 means it is working through its backoff, which can far
        // exceed the threshold. Flagging those buries the real stalls in noise
        // from a target that is merely down.
        track("m1");
        tracker.markRetrying("m1");
        clock.advance(Duration.ofHours(2));

        assertThat(detector(StallDetector.Config.REPORT_ONLY).sweep().stalled()).isEmpty();
        assertThat(warnings.raised).isEmpty();
    }

    @Test
    @DisplayName("reporting only is the default — nothing is returned to the queue")
    void reportOnlyReleasesNothing() {
        // A stall is a symptom of something this process does not understand.
        // Returning the message makes it another instance's problem and
        // destroys the evidence, so forcing is an operator's decision.
        track("m1");
        clock.advance(Duration.ofHours(1));

        var sweep = detector(StallDetector.Config.REPORT_ONLY).sweep();

        assertThat(sweep.released()).isZero();
        assertThat(queue.nacked).isEmpty();
        assertThat(tracker.size()).as("still owned, still visible to an operator").isOne();
    }

    @Test
    @DisplayName("with forcing on, a message past the force threshold goes back to its queue")
    void forcedMessageIsReturned() {
        track("m1");
        clock.advance(Duration.ofSeconds(600));

        var sweep = detector(new StallDetector.Config(true, null, null)).sweep();

        assertThat(sweep.released()).isOne();
        assertThat(queue.nacked).containsEntry("m1", StallDetector.FORCE_NACK_DELAY);
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("forcing waits for its own longer threshold, not the reporting one")
    void forcingHasItsOwnThreshold() {
        track("m1");
        clock.advance(Duration.ofSeconds(400)); // stalled, but not yet forceable

        var sweep = detector(new StallDetector.Config(true, null, null)).sweep();

        assertThat(sweep.stalled()).hasSize(1);
        assertThat(sweep.released()).isZero();
        assertThat(queue.nacked).isEmpty();
    }

    @Test
    @DisplayName("a failed nack keeps the entry, so the next sweep tries again")
    void failedNackKeepsOwnership() {
        // Releasing first would leave the message owned by nobody and still
        // invisible at the broker — lost until its visibility lapses, with
        // nothing tracking it.
        track("m1");
        clock.advance(Duration.ofSeconds(600));
        queue.failing = true;

        var sweep = detector(new StallDetector.Config(true, null, null)).sweep();

        assertThat(sweep.released()).isZero();
        assertThat(tracker.size()).as("still owned, so it is retried").isOne();
    }

    @Test
    @DisplayName("a message whose queue is gone is reported but not released")
    void unknownQueueIsReportedOnly() {
        var now = clock.instant();
        tracker.register(new InFlightMessage("m1", "b1", "", "q://vanished", now, now, "", "1", "r1", 0));
        clock.advance(Duration.ofSeconds(600));

        var sweep = detector(new StallDetector.Config(true, null, null)).sweep();

        assertThat(sweep.stalled()).hasSize(1);
        assertThat(sweep.released()).isZero();
        assertThat(tracker.size()).isOne();
    }

    @Test
    @DisplayName("a still-stalled message is reported again on the next sweep")
    void stallIsReportedRepeatedly() {
        // A stall that stops being mentioned reads as resolved.
        track("m1");
        clock.advance(Duration.ofSeconds(300));
        var detector = detector(StallDetector.Config.REPORT_ONLY);

        detector.sweep();
        clock.advance(Duration.ofSeconds(60));
        detector.sweep();

        assertThat(warnings.raised).hasSize(2);
        assertThat(warnings.raised.getLast()).contains("360s");
    }

    @Test
    @DisplayName("a message that named no pool reads as such rather than leaving a gap")
    void unnamedPoolIsDescribed() {
        track("m1");
        clock.advance(Duration.ofSeconds(300));

        detector(StallDetector.Config.REPORT_ONLY).sweep();

        assertThat(warnings.raised).singleElement().asString().contains("(none named)");
    }

    private void track(String id) {
        var now = clock.instant();
        tracker.register(new InFlightMessage(id, "b-" + id, "", queue.identifier(), now, now, "", "1", "r-" + id, 0));
    }

    private static final class FakeQueue implements Acknowledger {
        private final String id;
        volatile boolean failing;
        final Map<String, Duration> nacked = new ConcurrentHashMap<>();

        FakeQueue(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public boolean ack(QueuedMessage message) {
            return true;
        }

        @Override
        public boolean honoursDelayedReturn() {
            return true;
        }

        @Override
        public void defer(QueuedMessage message, Duration delay) {
            nack(message, delay);
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
            if (failing) {
                throw new IllegalStateException("broker unreachable");
            }
            nacked.put(message.id(), delay);
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

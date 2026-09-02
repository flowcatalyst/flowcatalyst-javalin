package io.flowcatalyst.router.lifecycle;

import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.queue.QueueMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` §9.3.
class BrokerStatsCacheTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final BrokerStatsCache cache = new BrokerStatsCache(clock);

    @Test
    @DisplayName("before the first refresh the age says never, not zero")
    void ageBeforeFirstRefresh() {
        // Zero would read as "just refreshed", which is the opposite of true.
        assertThat(cache.ageSeconds()).isEqualTo(BrokerStatsCache.NEVER_REFRESHED);
        assertThat(cache.latest()).isEmpty();
    }

    @Test
    @DisplayName("a refresh records the latest reading and resets the age")
    void refreshRecords() {
        refresh(Map.of("q1", metrics(5, 2, 100, 90, 10)));

        assertThat(cache.ageSeconds()).isZero();
        assertThat(cache.latest().get("q1").pending()).isEqualTo(5);

        clock.advance(Duration.ofSeconds(45));
        assertThat(cache.ageSeconds()).isEqualTo(45);
    }

    @Test
    @DisplayName("a queue that cannot be read keeps its previous reading rather than reporting zero")
    void unreadableQueueKeepsItsLastReading() {
        // "We could not ask" is not "there is nothing there". Showing zero
        // depth for a queue we failed to reach would be actively misleading
        // during exactly the outage someone is looking at it for.
        refresh(Map.of("q1", metrics(9, 3, 100, 90, 10)));

        cache.refresh(Map.of("q1", Optional::empty));

        assertThat(cache.latest().get("q1").pending()).isEqualTo(9);
    }

    @Test
    @DisplayName("a source that throws does not stop the other queues being sampled")
    void oneThrowingSourceDoesNotStopTheRest() {
        cache.refresh(Map.of(
                "bad", () -> {
                    throw new IllegalStateException("broker unreachable");
                },
                "good", () -> Optional.of(metrics(4, 1, 50, 40, 5))));

        assertThat(cache.latest()).containsOnlyKeys("good");
    }

    // ── §7.3: QUEUE_HEALTH ─────────────────────────────────────────────

    @Test
    @DisplayName("§7.3: a queue whose metrics go unreadable raises exactly one QUEUE_HEALTH warning across a failing streak")
    void unreachableQueueWarnsOnce() {
        var warnings = new RecordingWarnings();
        var withWarnings = new BrokerStatsCache(clock, warnings);

        withWarnings.refresh(Map.of("q1", Optional::empty));
        withWarnings.refresh(Map.of("q1", Optional::empty));
        withWarnings.refresh(Map.of("q1", Optional::empty));

        assertThat(warnings.raised).hasSize(1);
        assertThat(warnings.raised.getFirst()).contains("WARNING").contains("QUEUE_HEALTH").contains("q1");
    }

    @Test
    @DisplayName("§7.3: recovery raises an INFO QUEUE_HEALTH notice, and a later failure warns again")
    void recoveryRaisesInfoThenWarnsAgain() {
        var warnings = new RecordingWarnings();
        var withWarnings = new BrokerStatsCache(clock, warnings);

        withWarnings.refresh(Map.of("q1", Optional::empty));
        withWarnings.refresh(Map.of("q1", () -> Optional.of(metrics(1, 0, 1, 1, 0))));
        withWarnings.refresh(Map.of("q1", Optional::empty));

        assertThat(warnings.raised).hasSize(3);
        assertThat(warnings.raised.get(0)).contains("WARNING").contains("QUEUE_HEALTH");
        assertThat(warnings.raised.get(1)).contains("INFO").contains("QUEUE_HEALTH").contains("recovered");
        assertThat(warnings.raised.get(2)).contains("WARNING").contains("QUEUE_HEALTH");
    }

    @Test
    @DisplayName("§7.3: a throwing source is unreachable too, and shares the same once-per-streak warning")
    void throwingSourceWarnsLikeAnEmptyOne() {
        var warnings = new RecordingWarnings();
        var withWarnings = new BrokerStatsCache(clock, warnings);

        withWarnings.refresh(Map.of("bad", () -> {
            throw new IllegalStateException("broker unreachable");
        }));
        withWarnings.refresh(Map.of("bad", () -> {
            throw new IllegalStateException("still unreachable");
        }));

        assertThat(warnings.raised).hasSize(1);
        assertThat(warnings.raised.getFirst()).contains("WARNING").contains("QUEUE_HEALTH").contains("bad");
    }

    @Test
    @DisplayName("no window returns lifetime counters unchanged")
    void noWindowIsLifetime() {
        refresh(Map.of("q1", metrics(5, 2, 100, 90, 10)));

        assertThat(cache.windowed(null).get("q1").acked()).isEqualTo(90);
        assertThat(cache.windowed(Duration.ZERO).get("q1").acked()).isEqualTo(90);
    }

    @Test
    @DisplayName("a window reports the delta against its baseline, not the lifetime total")
    void windowReportsDelta() {
        // "How many since the process started" is a question nobody asks.
        refresh(Map.of("q1", metrics(5, 2, 100, 90, 10)));
        clock.advance(Duration.ofMinutes(5));
        refresh(Map.of("q1", metrics(7, 1, 160, 145, 12)));

        var windowed = cache.windowed(Duration.ofMinutes(5)).get("q1");

        assertThat(windowed.polled()).isEqualTo(60);
        assertThat(windowed.acked()).isEqualTo(55);
        assertThat(windowed.nacked()).isEqualTo(2);
    }

    @Test
    @DisplayName("depths are reported as levels, not differenced")
    void depthsAreLevelsNotCounters() {
        // "pending changed by 4" is not what a queue-depth column means.
        refresh(Map.of("q1", metrics(5, 2, 100, 90, 10)));
        clock.advance(Duration.ofMinutes(5));
        refresh(Map.of("q1", metrics(7, 1, 160, 145, 12)));

        var windowed = cache.windowed(Duration.ofMinutes(5)).get("q1");

        assertThat(windowed.pending()).isEqualTo(7);
        assertThat(windowed.inFlight()).isEqualTo(1);
    }

    @Test
    @DisplayName("a counter that went backwards saturates at zero rather than going negative")
    void deltaSaturatesAtZero() {
        // A counter going backwards means the consumer was rebuilt and its
        // process-local counters restarted — not that messages were un-acked.
        refresh(Map.of("q1", metrics(5, 2, 100, 90, 10)));
        clock.advance(Duration.ofMinutes(5));
        refresh(Map.of("q1", metrics(5, 2, 3, 2, 0)));

        var windowed = cache.windowed(Duration.ofMinutes(5)).get("q1");

        assertThat(windowed.polled()).isZero();
        assertThat(windowed.acked()).isZero();
    }

    @Test
    @DisplayName("a queue that appeared after the baseline reads as zero, not as its whole history")
    void newQueueIsNotASpike() {
        refresh(Map.of("q1", metrics(1, 0, 10, 10, 0)));
        clock.advance(Duration.ofMinutes(5));
        refresh(Map.of("q1", metrics(1, 0, 20, 20, 0), "fresh", metrics(3, 1, 500, 400, 5)));

        var windowed = cache.windowed(Duration.ofMinutes(5)).get("fresh");

        assertThat(windowed.acked()).as("no baseline means no measurable activity in the window").isZero();
        assertThat(windowed.pending()).as("its depth is still real").isEqualTo(3);
    }

    @Test
    @DisplayName("a window wider than the history falls back to the oldest snapshot")
    void widerThanHistoryUsesTheOldest() {
        refresh(Map.of("q1", metrics(1, 0, 10, 10, 0)));
        clock.advance(Duration.ofMinutes(2));
        refresh(Map.of("q1", metrics(1, 0, 30, 28, 1)));

        // Asking for an hour when we hold two minutes: the honest answer is
        // everything we can see, not nothing.
        var windowed = cache.windowed(Duration.ofHours(1)).get("q1");

        assertThat(windowed.acked()).isEqualTo(18);
    }

    @Test
    @DisplayName("history is trimmed but always keeps a usable baseline for the widest window")
    void historyIsTrimmedButKeepsABaseline() {
        for (int i = 0; i < 40; i++) {
            refresh(Map.of("q1", metrics(1, 0, i * 10L, i * 9L, i)));
            clock.advance(Duration.ofMinutes(1));
        }

        // 40 minutes of one-minute snapshots, held to a 30-minute history.
        assertThat(cache.historySize()).isLessThanOrEqualTo(32);
        assertThat(cache.windowed(BrokerStatsCache.HISTORY).get("q1").acked())
                .as("the widest supported window is still answerable").isPositive();
    }

    private void refresh(Map<String, QueueMetrics> readings) {
        var sources = new java.util.LinkedHashMap<String, Supplier<Optional<QueueMetrics>>>();
        readings.forEach((id, m) -> sources.put(id, () -> Optional.of(m)));
        cache.refresh(sources);
    }

    private static QueueMetrics metrics(long pending, long inFlight, long polled, long acked, long nacked) {
        return new QueueMetrics(pending, inFlight, polled, acked, nacked);
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

package io.flowcatalyst.router.observability;

import io.flowcatalyst.router.support.AllocProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/// The admission path reads these on every deferral; they must not copy the sample ring.
class PoolMetricsAllocationTest {

    private static final class FixedClock extends Clock {
        private final Instant now = Instant.parse("2026-01-01T00:10:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static PoolMetricsCollector loaded() {
        // Samples are stamped by the clock at record time; step a mutable clock then freeze.
        var stepping = new Clock() {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        var collector = new PoolMetricsCollector(stepping);
        for (int i = 0; i < 10_000; i++) {
            stepping.now = stepping.now.plusMillis(50);
            collector.recordSuccess(Duration.ofMillis(5));
        }
        return collector;
    }

    @Test
    @DisplayName("completionCount over 10,000 samples allocates a small constant, not a copy of the ring")
    void completionCountDoesNotCopy() {
        var collector = loaded();
        double bytes = AllocProbe.bytesPerCall(20_000, 20_000,
                () -> collector.completionCount(Duration.ofSeconds(30)));
        System.out.println("ALLOC completionCount bytes/call=" + bytes);
        // Copying 10,000 refs is >= 40 KB per call.
        assertThat(bytes).isLessThan(512);
    }

    @Test
    @DisplayName("completionRate over 10,000 samples allocates a small constant, not a copy of the ring")
    void completionRateDoesNotCopy() {
        var collector = loaded();
        double bytes = AllocProbe.bytesPerCall(20_000, 20_000,
                () -> collector.completionRate(Duration.ofSeconds(30)));
        System.out.println("ALLOC completionRate bytes/call=" + bytes);
        assertThat(bytes).isLessThan(512);
    }

    @Test
    @DisplayName("usableCompletionRate over 10,000 samples allocates a small constant")
    void usableCompletionRateDoesNotCopy() {
        var collector = loaded();
        double bytes = AllocProbe.bytesPerCall(20_000, 20_000,
                () -> collector.usableCompletionRate(Duration.ofSeconds(30), 4));
        System.out.println("ALLOC usableCompletionRate bytes/call=" + bytes);
        assertThat(bytes).isLessThan(512);
    }

    @Test
    @DisplayName("usableCompletionRate equals the count-gated completionRate")
    void usableRateMatchesComposition() {
        var collector = loaded();
        for (var w : new Duration[]{Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(30)}) {
            for (int min : new int[]{0, 1, 4, 100, 1000, 100_000}) {
                var expected = collector.completionCount(w) >= Math.max(min, 1)
                        ? collector.completionRate(w) : java.util.OptionalDouble.empty();
                assertThat(collector.usableCompletionRate(w, min)).isEqualTo(expected);
            }
        }
        assertThat(collector.completionCount(Duration.ofSeconds(5))).isEqualTo(101);
    }
}

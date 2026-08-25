package io.flowcatalyst.router.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/router.md` §2.10, §9.2, constants 43-44.
class PoolMetricsCollectorTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    @DisplayName("defaults match constant 43: 10000 samples, 5 min short window, 30 min long window")
    void defaultsMatchConstant43() {
        assertThat(PoolMetricsCollector.Config.DEFAULTS.maxSamples()).isEqualTo(10_000);
        assertThat(PoolMetricsCollector.Config.DEFAULTS.shortWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(PoolMetricsCollector.Config.DEFAULTS.longWindow()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("a fresh collector reports empty windows and a 100% success rate")
    void freshCollectorIsEmpty() {
        var collector = new PoolMetricsCollector(clock);
        var snap = collector.snapshot();

        assertThat(snap.totalSuccess()).isZero();
        assertThat(snap.totalFailure()).isZero();
        assertThat(snap.totalRateLimited()).isZero();
        assertThat(snap.totalSuppressed()).isZero();
        assertThat(snap.successRate()).isEqualTo(1.0);
        assertThat(snap.processingTime()).isEqualTo(PoolMetricsCollector.ProcessingTimeMetrics.EMPTY);
        assertThat(snap.last5Min().successCount()).isZero();
        assertThat(snap.last30Min().successCount()).isZero();
    }

    @Test
    @DisplayName("recordSuccess/recordFailure feed the cumulative totals and windowed counts identically")
    void countersAndWindowsAgree() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(10));
        collector.recordSuccess(Duration.ofMillis(20));
        collector.recordFailure(Duration.ofMillis(30));

        var snap = collector.snapshot();
        assertThat(snap.totalSuccess()).isEqualTo(2);
        assertThat(snap.totalFailure()).isEqualTo(1);
        assertThat(snap.successRate()).isEqualTo(2.0 / 3.0);
        assertThat(snap.last5Min().successCount()).isEqualTo(2);
        assertThat(snap.last5Min().failureCount()).isEqualTo(1);
        assertThat(snap.last30Min().successCount()).isEqualTo(2);
        assertThat(snap.last30Min().failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordTransient adds a non-success sample but does NOT bump totalFailure")
    void transientDoesNotCountAsFailureTotal() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordTransient(Duration.ofMillis(5));

        var snap = collector.snapshot();
        // Pins: totalFailure stays 0 (transient is not a verdict yet)...
        assertThat(snap.totalFailure()).isZero();
        // ...but the windowed failureCount sees it as non-success, so a
        // pool retrying everything doesn't read as 100% healthy.
        assertThat(snap.last5Min().failureCount()).isEqualTo(1);
        assertThat(snap.last5Min().successCount()).isZero();
    }

    @Test
    @DisplayName("recordRateLimited bumps the total but records no latency sample")
    void rateLimitedHasNoLatencySample() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordRateLimited();

        var snap = collector.snapshot();
        assertThat(snap.totalRateLimited()).isEqualTo(1);
        assertThat(snap.last5Min().rateLimitedCount()).isEqualTo(1);
        // Pins: rate limiting is not a delivery attempt, so it must not
        // pollute the latency percentiles.
        assertThat(snap.processingTime().sampleCount()).isZero();
    }

    @Test
    @DisplayName("recordSuppressed bumps its own total, distinct from success/failure/rateLimited")
    void suppressedIsCountedSeparately() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuppressed();
        collector.recordSuppressed();

        var snap = collector.snapshot();
        assertThat(snap.totalSuppressed()).isEqualTo(2);
        assertThat(snap.last5Min().suppressedCount()).isEqualTo(2);
        assertThat(snap.totalSuccess()).isZero();
        assertThat(snap.totalFailure()).isZero();
        assertThat(snap.totalRateLimited()).isZero();
    }

    @Test
    @DisplayName("a sample exactly at the 5-minute boundary is still inside last5Min")
    void sampleAtShortWindowBoundaryIsIncluded() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(1));

        // Advance to exactly 5 minutes later: age == ShortWindow, and Go's
        // cutoff test is `ts.Before(cutoff)`, so a sample AT the cutoff is
        // NOT before it -> still counted. If the boundary were off-by-one
        // (e.g. <= instead of <, or advancing past by even 1ms flips it),
        // this assertion fails.
        clock.advance(Duration.ofMinutes(5));

        var snap = collector.snapshot();
        assertThat(snap.last5Min().successCount())
                .as("sample at exactly the window boundary must still count")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a sample one millisecond past the 5-minute boundary falls out of last5Min but stays in last30Min")
    void sampleJustPastShortWindowBoundaryIsExcluded() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(1));

        clock.advance(Duration.ofMinutes(5).plusMillis(1));

        var snap = collector.snapshot();
        assertThat(snap.last5Min().successCount())
                .as("sample 1ms past the 5-minute boundary must be excluded")
                .isZero();
        assertThat(snap.last30Min().successCount())
                .as("the same sample is still well within the 30-minute window")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a sample past the 30-minute boundary falls out of both windows")
    void sampleAgesOutOfLongWindow() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(1));

        clock.advance(Duration.ofMinutes(30).plusMillis(1));

        var snap = collector.snapshot();
        assertThat(snap.last5Min().successCount()).isZero();
        assertThat(snap.last30Min().successCount()).isZero();
        // But the all-time total is unaffected by aging out of the windows.
        assertThat(snap.totalSuccess()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sample exactly at the 30-minute boundary is still inside last30Min")
    void sampleAtLongWindowBoundaryIsIncluded() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(1));

        clock.advance(Duration.ofMinutes(30));

        var snap = collector.snapshot();
        assertThat(snap.last30Min().successCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sample older than the long window is physically dropped from the retained buffer, not just excluded from windows")
    void samplesOlderThanLongWindowAreEvictedFromTheBuffer() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(999)); // will age out
        clock.advance(Duration.ofMinutes(30).plusMillis(1));
        collector.recordSuccess(Duration.ofMillis(1)); // fresh sample, triggers the trim

        var snap = collector.snapshot();
        // If the aged-out sample were still retained, sampleCount would be 2
        // and min/avg would be dragged toward 999ms.
        assertThat(snap.processingTime().sampleCount()).isEqualTo(1);
        assertThat(snap.processingTime().minMs()).isEqualTo(1);
        assertThat(snap.processingTime().maxMs()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordRateLimited events age out of last5Min but stay in last30Min across the boundary")
    void rateLimitedEventsAgeOutOfShortWindowOnly() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordRateLimited();

        clock.advance(Duration.ofMinutes(5).plusMillis(1));

        var snap = collector.snapshot();
        assertThat(snap.last5Min().rateLimitedCount()).isZero();
        assertThat(snap.last30Min().rateLimitedCount()).isEqualTo(1);
        assertThat(snap.totalRateLimited()).isEqualTo(1);
    }

    @Test
    @DisplayName("the sample cap (constant 43 MaxSamples) evicts the oldest sample, not the newest")
    void sampleCapEvictsOldest() {
        var config = new PoolMetricsCollector.Config(3, Duration.ofMinutes(5), Duration.ofMinutes(30));
        var collector = new PoolMetricsCollector(config, clock);

        collector.recordSuccess(Duration.ofMillis(100)); // will be evicted
        clock.advance(Duration.ofSeconds(1));
        collector.recordSuccess(Duration.ofMillis(200));
        clock.advance(Duration.ofSeconds(1));
        collector.recordSuccess(Duration.ofMillis(300));
        clock.advance(Duration.ofSeconds(1));
        collector.recordSuccess(Duration.ofMillis(400)); // pushes the buffer over 3

        var snap = collector.snapshot();
        // Pins: exactly 3 retained (the cap), and specifically the OLDEST
        // (100ms) was dropped -- if newest were dropped instead, minMs would
        // be 100 not 200.
        assertThat(snap.processingTime().sampleCount()).isEqualTo(3);
        assertThat(snap.processingTime().minMs()).isEqualTo(200);
        assertThat(snap.processingTime().maxMs()).isEqualTo(400);
    }

    @Test
    @DisplayName("percentiles use nearest-rank over the retained samples")
    void percentilesUseNearestRank() {
        var collector = new PoolMetricsCollector(clock);
        // 10 samples: 10,20,...,100ms. nearest-rank p50 = ceil(0.5*10)-1 = idx4 -> 50ms;
        // p95 = ceil(0.95*10)-1 = idx9 -> 100ms; p99 same -> 100ms.
        for (int i = 1; i <= 10; i++) {
            collector.recordSuccess(Duration.ofMillis(i * 10L));
        }

        var pt = collector.snapshot().processingTime();
        assertThat(pt.sampleCount()).isEqualTo(10);
        assertThat(pt.minMs()).isEqualTo(10);
        assertThat(pt.maxMs()).isEqualTo(100);
        assertThat(pt.avgMs()).isEqualTo(55.0);
        assertThat(pt.p50Ms()).isEqualTo(50);
        assertThat(pt.p95Ms()).isEqualTo(100);
        assertThat(pt.p99Ms()).isEqualTo(100);
    }

    @Test
    @DisplayName("throughputPerSec divides the windowed total by the window length, not elapsed time")
    void throughputUsesWindowLengthNotElapsedTime() {
        var config = new PoolMetricsCollector.Config(100, Duration.ofSeconds(10), Duration.ofMinutes(30));
        var collector = new PoolMetricsCollector(config, clock);
        for (int i = 0; i < 5; i++) {
            collector.recordSuccess(Duration.ofMillis(1));
        }

        var snap = collector.snapshot();
        // 5 deliveries over a 10s window -> 0.5/s, regardless of how much
        // wall-clock time actually elapsed while recording them.
        assertThat(snap.last5Min().throughputPerSec()).isEqualTo(0.5);
        assertThat(snap.last5Min().windowDurationSecs()).isEqualTo(10);
    }

    @Test
    @DisplayName("windowStart is now minus the window length at snapshot time")
    void windowStartIsNowMinusWindow() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(1));

        var snap = collector.snapshot();
        assertThat(snap.last5Min().windowStart()).isEqualTo(clock.instant().minus(Duration.ofMinutes(5)));
        assertThat(snap.last30Min().windowStart()).isEqualTo(clock.instant().minus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("the histogram counts observations cumulatively into every bucket >= the duration, and +Inf equals the total count")
    void histogramIsCumulative() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordSuccess(Duration.ofMillis(3));   // 0.003s
        collector.recordFailure(Duration.ofMillis(600)); // 0.6s
        collector.recordTransient(Duration.ofSeconds(20)); // past every finite bucket

        var hist = collector.histogramSnapshot();
        assertThat(hist.count()).isEqualTo(3);
        assertThat(hist.sumSeconds()).isEqualTo(0.003 + 0.6 + 20.0);
        // 0.003s (the only observation <= 0.5s) falls into every bucket
        // from 0.005 up to and including 0.5 -- cumulative "le" semantics,
        // so each of those buckets counts exactly 1, not the 0.6s sample.
        int bucket005 = indexOf(hist.bounds(), 0.005);
        int bucket05 = indexOf(hist.bounds(), 0.5);
        assertThat(hist.counts()[bucket005]).as("bucket 0.005s: only the 0.003s sample qualifies").isEqualTo(1);
        assertThat(hist.counts()[bucket05]).as("bucket 0.5s: still only the 0.003s sample, not the 0.6s one").isEqualTo(1);
        // 0.6s crosses into the 1s bucket, so from here both the 0.003s and
        // 0.6s samples are <= the bound.
        int bucket1 = indexOf(hist.bounds(), 1.0);
        assertThat(hist.counts()[bucket1]).as("bucket 1s: now includes the 0.6s sample too").isEqualTo(2);
        // 20s exceeds every finite bucket (largest is 10s), so the widest
        // bucket never reaches the full count of 3.
        int bucket10 = indexOf(hist.bounds(), 10.0);
        assertThat(hist.counts()[bucket10])
                .as("even the widest finite bucket excludes the 20s sample")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("rateLimited and suppressed events do not feed the histogram")
    void nonDeliveryEventsDoNotFeedHistogram() {
        var collector = new PoolMetricsCollector(clock);
        collector.recordRateLimited();
        collector.recordSuppressed();

        assertThat(collector.histogramSnapshot().count()).isZero();
    }

    @Test
    @DisplayName("Config rejects a non-positive maxSamples, and a long window shorter than the short window")
    void configValidates() {
        assertThatThrownBy(() -> new PoolMetricsCollector.Config(0, Duration.ofMinutes(5), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PoolMetricsCollector.Config(10, Duration.ofMinutes(30), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int indexOf(double[] bounds, double value) {
        for (int i = 0; i < bounds.length; i++) {
            if (bounds[i] == value) {
                return i;
            }
        }
        throw new IllegalArgumentException("no such bound: " + value);
    }

    private static final class TestClock extends Clock {
        private Instant now;

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

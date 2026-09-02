package io.flowcatalyst.router.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` §13 Q12 (R-12, ruled 2026-09-02), constant 34.
class BreakerRegistryTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final BreakerRegistry registry =
            new BreakerRegistry(CircuitBreaker.Config.DEFAULTS, clock);

    @Test
    @DisplayName("a breaker is created closed on first use and reused after")
    void createdOnFirstUse() {
        var first = registry.get("https://a.test/hook");

        assertThat(first.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(registry.get("https://a.test/hook")).isSameAs(first);
        assertThat(registry.size()).isOne();
    }

    @Test
    @DisplayName("endpoints trip independently")
    void endpointsAreIndependent() {
        IntStream.range(0, 10).forEach(i -> registry.get("https://a.test/hook").recordFailure());

        assertThat(registry.get("https://a.test/hook").state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.get("https://b.test/hook").state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("R-12: breaker key is origin + path — query string and fragment stripped, path still distinguishes")
    void breakerKeyIsOriginAndPathOnly() {
        // R-12, ruled 2026-09-02 (reverses the earlier "query string
        // separates breakers" behaviour): a query string is per-message data
        // and would otherwise fragment the failure signal so a genuinely
        // dead endpoint never trips.
        IntStream.range(0, 10).forEach(i -> registry.get("https://a.test/h?tenant=a").recordFailure());

        assertThat(registry.get("https://a.test/h?tenant=a").state()).isEqualTo(CircuitBreaker.State.OPEN);
        // A counter that must change if the key were wrong: identity, not
        // just state, so a mutation that keyed on the full URL is caught
        // even if it happened to leave both breakers open.
        assertThat(registry.get("https://a.test/h?tenant=b"))
                .as("same origin, same path, different query: ONE breaker")
                .isSameAs(registry.get("https://a.test/h?tenant=a"));
        assertThat(registry.get("https://a.test/h#section"))
                .as("a fragment is not part of the key either")
                .isSameAs(registry.get("https://a.test/h?tenant=a"));
        assertThat(registry.get("https://a.test/other").state())
                .as("a different PATH is a genuinely different endpoint")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("idle breakers are retired and come back closed")
    void evictsIdle() {
        var breaker = registry.get("https://a.test/hook");
        IntStream.range(0, 10).forEach(i -> breaker.recordFailure());
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofHours(2));

        assertThat(registry.evictIdle(Duration.ofHours(1))).isOne();
        assertThat(registry.size()).isZero();
        // An endpoint nobody has spoken to for an hour has no recent evidence
        // either way, so it starts clean rather than staying tripped forever.
        assertThat(registry.get("https://a.test/hook").state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("an active breaker survives eviction")
    void keepsActive() {
        registry.get("https://idle.test/hook");
        clock.advance(Duration.ofHours(2));
        registry.get("https://busy.test/hook").recordSuccess();

        assertThat(registry.evictIdle(Duration.ofHours(1))).isOne();
        assertThat(registry.snapshot()).containsOnlyKeys("https://busy.test/hook");
    }

    @Test
    @DisplayName("a non-positive idle window evicts nothing")
    void nonPositiveWindowIsNoOp() {
        registry.get("https://a.test/hook");
        clock.advance(Duration.ofDays(30));

        assertThat(registry.evictIdle(Duration.ZERO)).isZero();
        assertThat(registry.evictIdle(Duration.ofHours(-1))).isZero();
        assertThat(registry.size()).isOne();
    }

    @Test
    @DisplayName("reset clears one breaker and reports an unknown URL")
    void resetOne() {
        IntStream.range(0, 10).forEach(i -> registry.get("https://a.test/hook").recordFailure());

        assertThat(registry.reset("https://a.test/hook")).isTrue();
        assertThat(registry.get("https://a.test/hook").state()).isEqualTo(CircuitBreaker.State.CLOSED);
        // An operator typing the wrong URL is told, not silently succeeded.
        assertThat(registry.reset("https://nope.test/hook")).isFalse();
    }

    @Test
    @DisplayName("resetAll clears every breaker but keeps them visible")
    void resetAllKeepsEntries() {
        IntStream.range(0, 10).forEach(i -> registry.get("https://a.test/hook").recordFailure());
        registry.get("https://b.test/hook").recordSuccess();

        assertThat(registry.resetAll()).isEqualTo(2);
        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.snapshot().get("https://a.test/hook").state())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("the snapshot is a copy the caller cannot mutate")
    void snapshotIsImmutable() {
        registry.get("https://a.test/hook").recordSuccess();

        var snapshot = registry.snapshot();

        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get("https://a.test/hook").successes()).isOne();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.clear())
                .isInstanceOf(UnsupportedOperationException.class);
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

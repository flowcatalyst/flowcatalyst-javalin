package io.flowcatalyst.router.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Conformance for the Q3 collapse (`docs/spec/router.md` §13 Q3).
///
/// The ruling allows one policy to replace Go's two nested retry layers
/// **provided the observable behaviour is unchanged**, so these assert the
/// resulting schedule against the Go curves rather than against the Java's
/// own arithmetic.
class RetryPolicyTest {

    @Test
    @DisplayName("the flattened schedule reproduces Go's burst-then-backoff spacing")
    void flattenedSchedule() {
        // Go: 3 HTTP attempts spaced 1s/2s inside one Mediate, then the pool
        // backs off before the next Mediate. Flattened, with a 30s 5xx floor,
        // that is the sequence below. This is the assertion the ruling asks
        // for; everything else here explains one part of it.
        var schedule = IntStream.range(0, 10)
                .mapToObj(attempt -> RetryPolicy.DELIVERY.delayBefore(attempt, 30))
                .toList();

        assertThat(schedule).containsExactly(
                Duration.ZERO,            // 0 — first attempt, no wait
                Duration.ofSeconds(1),    // 1 — in-burst
                Duration.ofSeconds(2),    // 2 — in-burst
                Duration.ofSeconds(30),   // 3 — burst 1 gap, floored by the 5xx hint
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),   // 6 — burst 2 gap
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(30));  // 9 — burst 3 gap
    }

    @ParameterizedTest(name = "error curve: burst {0} with no floor waits {1} ms")
    @CsvSource({
            "0,100", "1,200", "2,400", "3,800", "4,1600", "5,3200", "6,6400",
            "7,12800", "8,25600", "9,51200", "10,102400", "11,204800",
            // 100ms << 12 = 409_600ms, above the 5-minute ceiling.
            "12,300000",
            // The shift is capped at 12, so it stays at the ceiling rather
            // than overflowing into a short delay.
            "13,300000", "40,300000", "2147483647,300000",
    })
    void errorCurve(int burst, long expectedMillis) {
        assertThat(RetryPolicy.DELIVERY.betweenBursts(burst, 0))
                .isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @ParameterizedTest(name = "deferred curve: attempt {0} waits {1} s")
    @CsvSource({"0,5", "1,10", "2,20", "3,40", "4,60", "5,60", "12,60"})
    void deferredCurve(int attempt, long expectedSeconds) {
        // Go's TestDeferredDelayCurve pins exactly 5,10,20,40,60.
        assertThat(RetryPolicy.DEFERRED.betweenBursts(attempt, 0))
                .isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    @DisplayName("a deferral makes no in-burst retry — the target is healthy")
    void deferredHasNoBurst() {
        assertThat(RetryPolicy.DEFERRED.burstSize()).isOne();

        var schedule = IntStream.range(0, 4)
                .mapToObj(attempt -> RetryPolicy.DEFERRED.delayBefore(attempt, 0))
                .toList();

        assertThat(schedule).containsExactly(
                Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("a server-requested delay raises a short backoff")
    void floorRaises() {
        // Burst 1 would be 200ms; a 429's Retry-After of 120s wins.
        assertThat(RetryPolicy.DELIVERY.betweenBursts(1, 120)).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("a server-requested delay cannot lift the backoff above the ceiling")
    void floorCannotLiftAboveCeiling() {
        // Order matters: floor first, then cap. Reversed, a target could pin
        // a worker for as long as it liked by sending a huge Retry-After.
        assertThat(RetryPolicy.DELIVERY.betweenBursts(1, 86_400)).isEqualTo(Duration.ofMinutes(5));
        assertThat(RetryPolicy.DEFERRED.betweenBursts(0, 3600)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("a negative requested delay is ignored rather than shortening the backoff")
    void negativeFloorIgnored() {
        assertThat(RetryPolicy.DELIVERY.betweenBursts(4, -99))
                .isEqualTo(RetryPolicy.DELIVERY.betweenBursts(4, 0));
    }

    @Test
    @DisplayName("burst boundaries are where the caller records breaker and metric outcomes")
    void burstBoundaries() {
        // Go records one breaker outcome per Mediate call, so three failed
        // HTTP attempts are ONE breaker failure. Recording per attempt would
        // open every breaker three times faster than today.
        var starts = IntStream.range(0, 10).filter(RetryPolicy.DELIVERY::startsBurst).boxed().toList();

        assertThat(starts).containsExactly(0, 3, 6, 9);
        assertThat(RetryPolicy.DELIVERY.burstSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("a negative attempt is a programming error, not a zero delay")
    void negativeAttemptRejected() {
        assertThatThrownBy(() -> RetryPolicy.DELIVERY.delayBefore(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the spacing is copied, so a shared policy cannot be mutated")
    void spacingIsCopied() {
        var mutable = new java.util.ArrayList<>(List.of(Duration.ofSeconds(1)));
        var policy = new RetryPolicy(mutable, Duration.ofMillis(100), Duration.ofMinutes(5), 12);

        mutable.clear();

        assertThat(policy.burstSpacing()).containsExactly(Duration.ofSeconds(1));
        assertThatThrownBy(() -> policy.burstSpacing().add(Duration.ZERO))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an impossible policy is rejected at construction")
    void invalidPolicies() {
        assertThatThrownBy(() -> new RetryPolicy(List.of(), Duration.ZERO, Duration.ofMinutes(1), 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(List.of(), Duration.ofSeconds(5), Duration.ofSeconds(1), 12))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

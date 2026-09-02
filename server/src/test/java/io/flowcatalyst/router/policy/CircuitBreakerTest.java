package io.flowcatalyst.router.policy;

import io.flowcatalyst.router.policy.CircuitBreaker.Admission;
import io.flowcatalyst.router.policy.CircuitBreaker.Config;
import io.flowcatalyst.router.policy.CircuitBreaker.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/router.md` §2.8, constants 30 and 31.
class CircuitBreakerTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker(Config.DEFAULTS, clock);

    @Test
    @DisplayName("a fresh breaker allows everything")
    void startsClosed() {
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.allow()).isEqualTo(new Admission.Allowed(false));
    }

    @Test
    @DisplayName("it trips on a failure RATE, not a failure count")
    void tripsOnRateNotCount() {
        // Nine failures among ninety calls is a 10% rate: an endpoint that is
        // mostly working must not be cut off just because it has failed a lot
        // in absolute terms.
        IntStream.range(0, 81).forEach(i -> breaker.recordSuccess());
        IntStream.range(0, 9).forEach(i -> breaker.recordFailure());

        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        assertThat(breaker.stats().failures()).isEqualTo(9);
    }

    @Test
    @DisplayName("it stays closed below minCalls even at a 100% failure rate")
    void minCallsGuardsSmallSamples() {
        // Without minCalls, the very first failure is a 1/1 rate and would
        // open the breaker on one bad call.
        IntStream.range(0, 9).forEach(i -> breaker.recordFailure());

        assertThat(breaker.state()).isEqualTo(State.CLOSED);

        breaker.recordFailure(); // the tenth reaches minCalls
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @ParameterizedTest(name = "{0} failures and {1} successes leaves the breaker {2}")
    @CsvSource({"5,5,OPEN", "4,6,CLOSED", "10,0,OPEN", "0,10,CLOSED"})
    void thresholdIsInclusive(int failures, int successes, State expected) {
        // The threshold is >=, so exactly 0.5 trips.
        IntStream.range(0, successes).forEach(i -> breaker.recordSuccess());
        IntStream.range(0, failures).forEach(i -> breaker.recordFailure());

        assertThat(breaker.state()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an open breaker rejects and tells the caller how long to wait")
    void openRejectsWithRetryAfter() {
        open();

        assertThat(breaker.allow()).isEqualTo(new Admission.Rejected(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("the reset timeout runs from the last failure, not from opening")
    void resetTimeoutRunsFromLastFailure() {
        open();
        clock.advance(Duration.ofSeconds(4));

        // An in-flight call fails while the breaker is already open: the wait
        // restarts. Otherwise a steadily-failing endpoint would be probed on
        // schedule regardless of how recently it failed.
        breaker.recordFailure();
        clock.advance(Duration.ofSeconds(4));
        assertThat(breaker.allow()).isInstanceOf(Admission.Rejected.class);

        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.allow()).isEqualTo(new Admission.Allowed(true));
    }

    @Test
    @DisplayName("the probe that opens the half-open window is marked")
    void halfOpenProbeIsMarked() {
        open();
        clock.advance(Duration.ofSeconds(5));

        assertThat(breaker.allow()).isEqualTo(new Admission.Allowed(true));
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
        // Q11, unruled: half-open admits every concurrent caller, not one
        // probe. Subsequent calls are allowed but no longer marked.
        assertThat(breaker.allow()).isEqualTo(new Admission.Allowed(false));
        assertThat(breaker.allow()).isEqualTo(new Admission.Allowed(false));
    }

    @Test
    @DisplayName("consecutive half-open successes close it and clear the window")
    void halfOpenClosesAfterThreshold() {
        open();
        clock.advance(Duration.ofSeconds(5));
        breaker.allow();

        breaker.recordSuccess();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);

        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
        // Clearing matters: the failures that opened it must not re-trip it
        // on the next single failure.
        assertThat(breaker.stats().recentFailures()).isZero();
        assertThat(breaker.stats().windowSize()).isZero();
    }

    @Test
    @DisplayName("one failure in half-open re-opens immediately")
    void halfOpenReopensOnAnyFailure() {
        open();
        clock.advance(Duration.ofSeconds(5));
        breaker.allow();
        breaker.recordSuccess();
        breaker.recordSuccess(); // one short of closing

        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(State.OPEN);
        // The tally resets, so a later half-open starts from zero rather than
        // inheriting credit from the attempt that just failed.
        clock.advance(Duration.ofSeconds(5));
        breaker.allow();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(State.HALF_OPEN);
    }

    @Test
    @DisplayName("the window slides, so old failures stop counting")
    void windowSlides() {
        var small = new CircuitBreaker(new Config(0.5, 4, 3, Duration.ofSeconds(5), 4), clock);
        IntStream.range(0, 2).forEach(i -> small.recordFailure());
        assertThat(small.state()).isEqualTo(State.CLOSED);

        // Fill past the buffer with successes: the two failures age out and
        // the rate falls back to zero rather than being remembered forever.
        IntStream.range(0, 4).forEach(i -> small.recordSuccess());

        assertThat(small.stats().recentFailures()).isZero();
        assertThat(small.stats().windowSize()).isEqualTo(4);
        assertThat(small.state()).isEqualTo(State.CLOSED);
    }

    @Test
    @DisplayName("cumulative counters survive the window, reset clears both")
    void cumulativeCountersAndReset() {
        IntStream.range(0, 6).forEach(i -> breaker.recordSuccess());
        IntStream.range(0, 6).forEach(i -> breaker.recordFailure());

        assertThat(breaker.stats().successes()).isEqualTo(6);
        assertThat(breaker.stats().failures()).isEqualTo(6);
        assertThat(breaker.state()).isEqualTo(State.OPEN);

        breaker.reset();

        assertThat(breaker.stats()).isEqualTo(new CircuitBreaker.Stats(State.CLOSED, 0, 0, 0, 0));
        assertThat(breaker.allow()).isEqualTo(new Admission.Allowed(false));
    }

    @ParameterizedTest(name = "{0} goes on the wire as {1}")
    @CsvSource({"CLOSED,CLOSED", "OPEN,OPEN", "HALF_OPEN,HALFOPEN"})
    void wireStrings(State state, String wire) {
        // Dashboards match on these exact strings.
        assertThat(state.wireValue()).isEqualTo(wire);
    }

    @Test
    @DisplayName("recordFailure returns Opened exactly on the call that trips the breaker")
    void recordFailureReturnsOpenedOnlyOnTheTrippingCall() {
        // The failure count on Opened is the window's failure count at the
        // moment it tripped — what an operator-facing warning would report.
        for (int i = 0; i < 9; i++) {
            assertThat(breaker.recordFailure())
                    .as("call %d must not trip a breaker still below minCalls", i)
                    .isEqualTo(new CircuitBreaker.Transition.None());
        }
        assertThat(breaker.recordFailure())
                .as("the tenth failure reaches minCalls at a 100%% rate")
                .isEqualTo(new CircuitBreaker.Transition.Opened(10));
        assertThat(breaker.state()).isEqualTo(State.OPEN);

        // Already open: further failures extend the wait but do not
        // re-trip, so they report None, not another Opened.
        assertThat(breaker.recordFailure()).isEqualTo(new CircuitBreaker.Transition.None());
    }

    @Test
    @DisplayName("recordFailure in half-open returns Opened on the probe that fails")
    void recordFailureReturnsOpenedOnHalfOpenReopen() {
        open();
        clock.advance(Duration.ofSeconds(5));
        breaker.allow();
        breaker.recordSuccess();

        assertThat(breaker.recordFailure())
                .as("one failure in half-open re-opens immediately")
                .isInstanceOf(CircuitBreaker.Transition.Opened.class);
        assertThat(breaker.state()).isEqualTo(State.OPEN);
    }

    @Test
    @DisplayName("recordSuccess returns Closed exactly on the call that closes the breaker")
    void recordSuccessReturnsClosedOnlyOnTheClosingCall() {
        open();
        clock.advance(Duration.ofSeconds(5));
        breaker.allow();

        assertThat(breaker.recordSuccess()).isEqualTo(new CircuitBreaker.Transition.None());
        assertThat(breaker.recordSuccess()).isEqualTo(new CircuitBreaker.Transition.None());
        assertThat(breaker.recordSuccess())
                .as("the third consecutive half-open success reaches successThreshold")
                .isEqualTo(new CircuitBreaker.Transition.Closed());
        assertThat(breaker.state()).isEqualTo(State.CLOSED);
    }

    @Test
    @DisplayName("recordSuccess on an already-closed breaker never reports a transition")
    void recordSuccessOnClosedBreakerReportsNone() {
        assertThat(breaker.recordSuccess()).isEqualTo(new CircuitBreaker.Transition.None());
    }

    @Test
    @DisplayName("an impossible config is rejected at construction")
    void invalidConfig() {
        assertThatThrownBy(() -> new Config(0, 10, 3, Duration.ofSeconds(5), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Config(1.5, 10, 3, Duration.ofSeconds(5), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Config(0.5, 0, 3, Duration.ofSeconds(5), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// Ten failures at the default config: enough calls to reach minCalls at
    /// a 100% rate.
    private void open() {
        IntStream.range(0, 10).forEach(i -> breaker.recordFailure());
        assertThat(breaker.state()).isEqualTo(State.OPEN);
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

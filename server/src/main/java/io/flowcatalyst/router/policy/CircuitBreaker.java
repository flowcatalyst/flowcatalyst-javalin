package io.flowcatalyst.router.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/// Per-endpoint failure-**rate** breaker (`docs/spec/router.md` §2.8).
///
/// It trips on a rate over a sliding window of recent calls, not on a raw
/// failure count: an endpoint serving thousands of calls an hour with a
/// steady trickle of failures should not open, while one failing half of ten
/// calls should. [Config#minCalls] stops a single early failure from reading
/// as a 100 % failure rate.
///
/// Recorded **once per burst**, not once per HTTP attempt — see
/// [RetryPolicy]. Three failed attempts inside one delivery are one failure
/// here.
public final class CircuitBreaker {

    /// The three-state lifecycle. Wire strings on the monitoring API are the
    /// [#wireValue] — note `HALFOPEN`, with no separator, which is what the
    /// Go emits and therefore what dashboards match on.
    public enum State {
        CLOSED, OPEN, HALF_OPEN;

        public String wireValue() {
            return this == HALF_OPEN ? "HALFOPEN" : name();
        }
    }

    /// Whether a call may proceed — an expected outcome, so a sealed result
    /// rather than an exception (CONVENTIONS §8).
    public sealed interface Admission {

        /// Proceed. `probe` marks the call that moved an open breaker to
        /// half-open, which is the one worth logging.
        record Allowed(boolean probe) implements Admission {
        }

        /// Do not call. `retryAfter` is how long the caller should defer —
        /// the breaker's reset timeout, which becomes the `CircuitOpen`
        /// outcome's delay (constant 31).
        record Rejected(Duration retryAfter) implements Admission {
        }
    }

    /// Thresholds. Defaults are constant 30 in the spec's timing table.
    public record Config(double failureRateThreshold, int minCalls, int successThreshold,
                         Duration resetTimeout, int bufferSize) {

        public static final Config DEFAULTS =
                new Config(0.5, 10, 3, Duration.ofSeconds(5), 100);

        public Config {
            if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
                throw new IllegalArgumentException("failureRateThreshold must be in (0,1]");
            }
            if (minCalls < 1 || successThreshold < 1 || bufferSize < 1) {
                throw new IllegalArgumentException("minCalls, successThreshold and bufferSize must be positive");
            }
            if (resetTimeout.isNegative()) {
                throw new IllegalArgumentException("resetTimeout must not be negative");
            }
        }
    }

    /// A snapshot for the monitoring API. `failures`/`successes` are
    /// cumulative since the last [#reset]; `recentFailures` is what is
    /// currently in the sliding window.
    public record Stats(State state, long successes, long failures, int recentFailures, int windowSize) {
    }

    private final Config config;
    private final Clock clock;

    /// Guards state, the window, the half-open tally and the last-failure
    /// instant — they are one decision and must move together.
    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.CLOSED;
    private final boolean[] window;
    private int head;
    private int count;
    private int windowFailures;
    private int halfOpenSuccesses;
    private Instant lastFailure;

    /// Cumulative, outside the lock: read for reporting, never for a decision.
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /// Last time this breaker was touched, for idle eviction by the registry.
    private volatile Instant lastActivity;

    public CircuitBreaker(Config config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.window = new boolean[config.bufferSize()];
        this.lastActivity = clock.instant();
    }

    /// Whether a call may proceed now.
    ///
    /// An open breaker becomes half-open once [Config#resetTimeout] has
    /// elapsed **since the last failure** — not since it opened, so a
    /// breaker that keeps being failed by in-flight calls stays open.
    ///
    /// Half-open then admits **every** concurrent caller, not a single probe
    /// (`docs/spec/router.md` §13 Q11, unruled — behaviour kept). Under load
    /// that means a burst reaches a recovering endpoint rather than one
    /// trial call.
    public Admission allow() {
        var now = clock.instant();
        lastActivity = now;
        lock.lock();
        try {
            if (state != State.OPEN) {
                return new Admission.Allowed(false);
            }
            if (lastFailure != null && !Duration.between(lastFailure, now).minus(config.resetTimeout()).isNegative()) {
                state = State.HALF_OPEN;
                halfOpenSuccesses = 0;
                return new Admission.Allowed(true);
            }
            return new Admission.Rejected(config.resetTimeout());
        } finally {
            lock.unlock();
        }
    }

    /// Records a delivery the endpoint handled. In half-open,
    /// [Config#successThreshold] consecutive successes close the breaker and
    /// **clear the window**, so a recovered endpoint starts from a clean
    /// slate rather than re-tripping on the failures that opened it.
    public void recordSuccess() {
        successes.incrementAndGet();
        var now = clock.instant();
        lastActivity = now;
        lock.lock();
        try {
            push(true);
            if (state == State.HALF_OPEN && ++halfOpenSuccesses >= config.successThreshold()) {
                state = State.CLOSED;
                clearWindow();
                halfOpenSuccesses = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /// Records a delivery the endpoint failed. Any failure in half-open
    /// re-opens immediately — one bad probe is enough, because the endpoint
    /// has already demonstrated it is not well.
    public void recordFailure() {
        failures.incrementAndGet();
        var now = clock.instant();
        lastActivity = now;
        lock.lock();
        try {
            lastFailure = now;
            push(false);
            switch (state) {
                case CLOSED -> {
                    if (count >= config.minCalls() && failureRate() >= config.failureRateThreshold()) {
                        state = State.OPEN;
                    }
                }
                case HALF_OPEN -> {
                    state = State.OPEN;
                    halfOpenSuccesses = 0;
                }
                case OPEN -> {
                    // Already open; the refreshed lastFailure above extends
                    // the wait, which is the intent.
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /// Forces the breaker closed and forgets everything — the operator
    /// override, and how the registry recycles an entry.
    public void reset() {
        successes.set(0);
        failures.set(0);
        lock.lock();
        try {
            state = State.CLOSED;
            clearWindow();
            halfOpenSuccesses = 0;
            lastFailure = null;
        } finally {
            lock.unlock();
        }
    }

    public Stats stats() {
        lock.lock();
        try {
            return new Stats(state, successes.get(), failures.get(), windowFailures, count);
        } finally {
            lock.unlock();
        }
    }

    public State state() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    /// Appends one sample, evicting the oldest once the window is full.
    /// [#windowFailures] is maintained incrementally rather than recounted,
    /// so the rate is O(1); the eviction adjustment is what keeps it honest.
    private void push(boolean ok) {
        if (count == window.length && !window[head]) {
            windowFailures--;
        }
        window[head] = ok;
        head = (head + 1) % window.length;
        if (count < window.length) {
            count++;
        }
        if (!ok) {
            windowFailures++;
        }
    }

    private void clearWindow() {
        head = 0;
        count = 0;
        windowFailures = 0;
    }

    private double failureRate() {
        return count == 0 ? 0 : (double) windowFailures / count;
    }
}

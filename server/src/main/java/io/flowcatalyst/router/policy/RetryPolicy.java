package io.flowcatalyst.router.policy;

import java.time.Duration;
import java.util.List;

/// When to make the next delivery attempt.
///
/// **This is the Q3 collapse** (`docs/spec/router.md` §13 Q3). Go retries in
/// two nested layers: up to three HTTP attempts inside one `Mediate` call,
/// spaced 1 s then 2 s, and *then* the pool schedules its own exponential
/// backoff before calling `Mediate` again. The owner ruled that becomes one
/// named policy provided the observable behaviour is unchanged, so this
/// object reproduces the *flattened* schedule rather than re-creating the
/// nesting:
///
/// ```
/// attempt:  0     1     2      3     4     5       6   …
/// delay:    —    1s    2s    B(1)   1s    2s     B(2) …
///                            └── between bursts ──┘
/// ```
///
/// where `B(n) = clamp(minDelay << min(n, shiftCap), floor, maxDelay)`.
///
/// ### Two orderings that are load-bearing
///
/// The floor is applied **before** the cap, so a server-requested delay can
/// raise a short backoff but can never lift it above [#maxDelay]. Reversing
/// them would let a target pin a worker for as long as it liked by sending a
/// large `Retry-After`.
///
/// The shift is capped at [#shiftCap] before shifting, not after — shifting a
/// 100 ms `Duration` by an unbounded attempt count overflows rather than
/// saturating, which would turn a long-failing target into an *immediate*
/// retry loop.
///
/// ### What this object deliberately does not decide
///
/// Breaker and metric accounting stay with the caller and remain **per
/// burst**, not per attempt. Go records one breaker outcome and one pool
/// metric sample per `Mediate` call, so a burst of three failed HTTP
/// attempts is one breaker failure. Flattening the schedule must not change
/// that: recording per attempt would open every circuit breaker three times
/// faster than today, which the Q3 ruling explicitly forbids. [#startsBurst]
/// is how a caller tells the two apart.
public record RetryPolicy(List<Duration> burstSpacing, Duration minDelay, Duration maxDelay, int shiftCap) {

    /// Guards the invariants the schedule depends on, and defensively copies
    /// the spacing so a caller cannot mutate a shared policy.
    public RetryPolicy {
        if (minDelay.isNegative() || minDelay.isZero()) {
            throw new IllegalArgumentException("minDelay must be positive");
        }
        if (maxDelay.compareTo(minDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be below minDelay");
        }
        if (shiftCap < 0) {
            throw new IllegalArgumentException("shiftCap must not be negative");
        }
        burstSpacing = List.copyOf(burstSpacing);
    }

    /// Failed deliveries — 5xx, transport errors, an open breaker.
    ///
    /// Three attempts per burst at 1 s and 2 s (constants 24/25), then the
    /// error curve: 100 ms doubling to a 5-minute ceiling, shift capped at 12
    /// (constants 13/14/15). With the usual 30 s floor for a 5xx the first
    /// nine bursts all wait 30 s, and the curve only overtakes the floor at
    /// burst 9 — which is the behaviour `TestRetryDelayKeepsTheErrorCurve`
    /// pins in Go.
    public static final RetryPolicy DELIVERY = new RetryPolicy(
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)),
            Duration.ofMillis(100),
            Duration.ofMinutes(5),
            12);

    /// Deferrals — a 2xx carrying `{"ack": false}`.
    ///
    /// **No burst**: the target is healthy and answering cheaply, so Go makes
    /// no in-call retry at all here. Its own curve starts higher and ceilings
    /// lower — 5, 10, 20, 40, 60 s (constant 17) — because recovery latency
    /// matters more than politeness toward a target that is plainly fine.
    public static final RetryPolicy DEFERRED = new RetryPolicy(
            List.of(),
            Duration.ofSeconds(5),
            Duration.ofMinutes(1),
            12);

    /// Attempts in one burst: the spacings, plus the attempt that starts it.
    public int burstSize() {
        return burstSpacing.size() + 1;
    }

    /// Whether `attempt` begins a new burst — the boundary at which a caller
    /// records breaker and metric outcomes. Attempt 0 starts the first burst.
    public boolean startsBurst(int attempt) {
        return attempt % burstSize() == 0;
    }

    /// Whether `attempt` is the last of its burst — the point at which a
    /// burst's verdict is known and a failure may be recorded against the
    /// circuit breaker.
    ///
    /// Go records one breaker outcome per `Mediate` call, *after* its in-call
    /// retries, so three failed HTTP attempts are one breaker failure.
    /// Recording each attempt would open every circuit three times faster
    /// than today, which the Q3 ruling forbids. Successes are recorded
    /// immediately regardless: a success ends the burst wherever it lands.
    public boolean endsBurst(int attempt) {
        return (attempt + 1) % burstSize() == 0;
    }

    /// How long to wait before making `attempt` (0-based), given any delay
    /// the server asked for in seconds.
    ///
    /// `floorSeconds` is the outcome's requested delay — `Retry-After` on a
    /// 429, the breaker's reset timeout, the 5xx hint. Zero or negative means
    /// none was requested.
    public Duration delayBefore(int attempt, int floorSeconds) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative: " + attempt);
        }
        if (attempt == 0) {
            return Duration.ZERO;
        }
        int positionInBurst = attempt % burstSize();
        if (positionInBurst != 0) {
            return burstSpacing.get(positionInBurst - 1);
        }
        return betweenBursts(attempt / burstSize(), floorSeconds);
    }

    /// The exponential backoff between bursts, for 1-based burst index `n`.
    Duration betweenBursts(int n, int floorSeconds) {
        var delay = minDelay.multipliedBy(1L << Math.min(n, shiftCap));
        var floor = Duration.ofSeconds(Math.max(floorSeconds, 0));
        if (delay.compareTo(floor) < 0) {
            delay = floor;
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}

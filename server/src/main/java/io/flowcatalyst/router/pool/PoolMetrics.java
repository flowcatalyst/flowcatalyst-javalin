package io.flowcatalyst.router.pool;

import java.time.Duration;
import java.util.OptionalDouble;

/// What a pool records about its own deliveries.
///
/// Kept as an interface so the pool's decisions can be asserted without a
/// metrics backend, and so a no-op implementation is trivial.
public interface PoolMetrics {

    /// A delivery the target accepted.
    void recordSuccess(Duration took);

    /// A delivery that failed terminally — it will not be retried.
    void recordFailure(Duration took);

    /// A delivery that failed but is being retried, so it is not yet a
    /// verdict on the target.
    void recordTransient(Duration took);

    /// The pool's own rate limiter held a message back. Counted separately
    /// from an HTTP 429, which is the *target* throttling us — conflating
    /// them hides which side is the bottleneck (`docs/spec/router.md` §13 Q9).
    void recordRateLimited();

    /// A message ACKed without delivery because its group was suppressed.
    ///
    /// Go records nothing here (§13 Q53), so a heavily flushed pool looks
    /// idle rather than suppressed. Counted from the start in Java: the
    /// blind spot is the reason the question exists.
    void recordSuppressed();

    /// The HTTP version a target actually negotiated for a delivered
    /// request (`docs/spec/router-h2.md` §3) — feeds the
    /// `fc_router_mediation_http_version_total{version="HTTP_2"|"HTTP_1_1"}`
    /// counter, the only way a target still stuck on 1.1 is visible once h2
    /// is preferred by default.
    void recordHttpVersion(HttpVersion version);

    /// Deliveries completed (success, failure or transient — every
    /// [#recordSuccess]/[#recordFailure]/[#recordTransient] call) within the
    /// last `window`, divided by the span since the OLDEST of them, floored
    /// at one second — the admission schedule's throughput estimate
    /// (`docs/spec/router-hol-deferral.md` §3, [PoolAdmission]). Dividing by
    /// `window` itself instead would understate a pool that only just woke
    /// up: one that completed ten deliveries in the last ten seconds runs at
    /// roughly one per second, not one per five minutes. Empty when there is
    /// no completion within `window` at all.
    OptionalDouble completionRate(Duration window);

    PoolMetrics NO_OP = new PoolMetrics() {
        @Override
        public void recordSuccess(Duration took) {
        }

        @Override
        public void recordFailure(Duration took) {
        }

        @Override
        public void recordTransient(Duration took) {
        }

        @Override
        public void recordRateLimited() {
        }

        @Override
        public void recordSuppressed() {
        }

        @Override
        public void recordHttpVersion(HttpVersion version) {
        }

        @Override
        public OptionalDouble completionRate(Duration window) {
            return OptionalDouble.empty();
        }
    };
}

package io.flowcatalyst.router.pool;

import java.time.Duration;

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
    };
}

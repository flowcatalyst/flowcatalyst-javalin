package io.flowcatalyst.router.policy;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/// Per-pool token bucket, configured in requests **per minute**.
///
/// Tokens accrue continuously at `rpm / 60` per second up to a burst of
/// `rpm` — a full minute's allowance (constant 32, flagged ACC? in the spec:
/// after an idle period the whole minute can fire at once). An `rpm` of zero
/// means unlimited, which is how a pool with no configured limit behaves.
///
/// ### Waiting reserves rather than polls
///
/// [#await] takes its token immediately, letting the balance go negative,
/// and sleeps off only its own debt. Waiters therefore drain in the order
/// they arrived, and each wakes when *its* token is due. Re-checking a shared
/// balance in a loop would instead wake every waiter on each refill and hand
/// the token to whoever won the race — a thundering herd whose starvation
/// grows with pool concurrency.
///
/// Cancellation is interruption (CONVENTIONS §8): [#await] throws
/// [InterruptedException] and never swallows it.
public final class RateLimiter {

    private static final double NANOS_PER_MINUTE = 60_000_000_000.0;

    private final LongSupplier nanoTime;
    private final ReentrantLock lock = new ReentrantLock();

    /// Guarded by [#lock]. `tokens` may be negative: that is reserved debt
    /// owed by callers already sleeping.
    private int requestsPerMinute;
    private double tokensPerNano;
    private double capacity;
    private double tokens;
    private long lastRefillNanos;

    public RateLimiter(int requestsPerMinute) {
        this(requestsPerMinute, System::nanoTime);
    }

    /// `nanoTime` is injected so tests can drive the bucket without sleeping.
    public RateLimiter(int requestsPerMinute, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.lastRefillNanos = nanoTime.getAsLong();
        reconfigure(requestsPerMinute);
    }

    /// Replaces the rate in place, so a config sync does not have to swap the
    /// limiter out from under callers already waiting on it.
    ///
    /// The bucket starts full at the new capacity: a pool whose limit was
    /// just raised should be able to use it immediately, and one whose limit
    /// was lowered is bounded by the new rate from here on regardless.
    public void reconfigure(int requestsPerMinute) {
        lock.lock();
        try {
            this.requestsPerMinute = Math.max(requestsPerMinute, 0);
            if (this.requestsPerMinute == 0) {
                this.capacity = 0;
                this.tokensPerNano = 0;
                this.tokens = 0;
            } else {
                this.capacity = Math.max(this.requestsPerMinute, 1);
                this.tokensPerNano = this.requestsPerMinute / NANOS_PER_MINUTE;
                this.tokens = this.capacity;
            }
            this.lastRefillNanos = nanoTime.getAsLong();
        } finally {
            lock.unlock();
        }
    }

    public int requestsPerMinute() {
        lock.lock();
        try {
            return requestsPerMinute;
        } finally {
            lock.unlock();
        }
    }

    /// Whether the bucket is empty *right now*.
    ///
    /// Observational only — it takes no token and changes nothing, so a
    /// metrics read cannot perturb the thing it measures. An unlimited
    /// limiter is never limited.
    public boolean limited() {
        lock.lock();
        try {
            return requestsPerMinute != 0 && available() < 1.0;
        } finally {
            lock.unlock();
        }
    }

    /// Takes a token if one is free right now. Never blocks, never reserves.
    public boolean tryAcquire() {
        lock.lock();
        try {
            if (requestsPerMinute == 0) {
                return true;
            }
            refill();
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /// Reserves a token and reports how long until it is due — zero when one
    /// was free. The caller owes the wait whether or not it honours it, which
    /// is what keeps waiters in order.
    public Duration reserve() {
        lock.lock();
        try {
            if (requestsPerMinute == 0) {
                return Duration.ZERO;
            }
            refill();
            tokens -= 1.0;
            return tokens >= 0 ? Duration.ZERO : Duration.ofNanos((long) Math.ceil(-tokens / tokensPerNano));
        } finally {
            lock.unlock();
        }
    }

    /// Blocks until this caller's token is due.
    ///
    /// @throws InterruptedException if the thread is interrupted while
    ///         waiting — the token stays reserved, which is deliberate: the
    ///         message is going back on a retry path, and releasing it would
    ///         let a shutdown burst exceed the configured rate.
    public void await() throws InterruptedException {
        var wait = reserve();
        if (!wait.isZero()) {
            Thread.sleep(wait);
        }
    }

    /// Accrues tokens for the elapsed time, capped at the burst.
    private void refill() {
        long now = nanoTime.getAsLong();
        long elapsed = now - lastRefillNanos;
        lastRefillNanos = now;
        if (elapsed <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
    }

    /// Tokens that would be available now, without mutating anything.
    private double available() {
        long elapsed = nanoTime.getAsLong() - lastRefillNanos;
        return elapsed <= 0 ? tokens : Math.min(capacity, tokens + elapsed * tokensPerNano);
    }
}

package io.flowcatalyst.platform.shared;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/// At most one log line per interval from one call site that can fire per
/// message — an unexpected failure on a hot path must leave a stack trace for
/// the operator without writing one per message. The admitted line carries how
/// many were held back since the previous one.
public final class LogThrottle {

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong nextAllowed;
    private final AtomicLong suppressed = new AtomicLong();

    public LogThrottle(Duration interval) {
        this(interval, System::nanoTime);
    }

    LogThrottle(Duration interval, LongSupplier nanoTime) {
        this.intervalNanos = interval.toNanos();
        this.nanoTime = nanoTime;
        this.nextAllowed = new AtomicLong(nanoTime.getAsLong());
    }

    /// Test seam: the next [#admit] is admitted (for a static throttle, whose
    /// window an earlier test in the same JVM may have spent).
    public void resetForTest() {
        nextAllowed.set(nanoTime.getAsLong());
        suppressed.set(0);
    }

    /// Present — the count suppressed since the last admitted line — when the
    /// caller should log now; empty when it should not (and it is counted).
    public OptionalLong admit() {
        long now = nanoTime.getAsLong();
        long next = nextAllowed.get();
        if (now - next >= 0 && nextAllowed.compareAndSet(next, now + intervalNanos)) {
            return OptionalLong.of(suppressed.getAndSet(0));
        }
        suppressed.incrementAndGet();
        return OptionalLong.empty();
    }
}

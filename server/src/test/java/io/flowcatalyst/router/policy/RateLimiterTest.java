package io.flowcatalyst.router.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` constant 32.
///
/// Time is a supplier the test drives, so the bucket's behaviour at exact
/// boundaries is assertable without sleeping through it.
class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(0);

    private RateLimiter limiter(int rpm) {
        return new RateLimiter(rpm, nanos::get);
    }

    private void advance(Duration by) {
        nanos.addAndGet(by.toNanos());
    }

    @Test
    @DisplayName("the bucket starts full, so a whole minute's allowance can fire at once")
    void startsFull() {
        // Constant 32, flagged ACC? in the spec: burst equals rpm, so after
        // an idle period the full minute is immediately available. Recorded
        // here because it is surprising, not because it is obviously right.
        var limiter = limiter(60);

        assertThat(IntStream.range(0, 60).allMatch(i -> limiter.tryAcquire())).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("tokens accrue continuously at rpm per minute")
    void tokensAccrue() {
        var limiter = limiter(60); // one per second
        IntStream.range(0, 60).forEach(i -> limiter.tryAcquire());

        advance(Duration.ofMillis(999));
        assertThat(limiter.tryAcquire()).isFalse();

        advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("accrual is capped at the burst, so idling does not bank credit")
    void accrualIsCapped() {
        var limiter = limiter(60);
        IntStream.range(0, 60).forEach(i -> limiter.tryAcquire());

        advance(Duration.ofHours(1));

        assertThat(IntStream.range(0, 60).allMatch(i -> limiter.tryAcquire())).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("rpm of zero is unlimited and never limited")
    void zeroIsUnlimited() {
        var limiter = limiter(0);

        assertThat(IntStream.range(0, 10_000).allMatch(i -> limiter.tryAcquire())).isTrue();
        assertThat(limiter.limited()).isFalse();
        assertThat(limiter.reserve()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("limited() observes without taking a token")
    void limitedIsObservational() {
        // The pool calls this to record a rate-limited metric; if it consumed
        // a token, measuring the limiter would throttle the pool.
        var limiter = limiter(2);

        assertThat(limiter.limited()).isFalse();
        assertThat(limiter.limited()).isFalse();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.limited()).isTrue();
    }

    @Test
    @DisplayName("reserving queues waiters in order rather than racing them")
    void reservationsQueueInOrder() {
        var limiter = limiter(60); // one token per second
        IntStream.range(0, 60).forEach(i -> limiter.tryAcquire());

        // Each reservation takes its slot immediately and owes its own wait,
        // so three waiters are due one, two and three seconds out — not all
        // woken at the same refill to fight over one token.
        assertThat(limiter.reserve()).isEqualTo(Duration.ofSeconds(1));
        assertThat(limiter.reserve()).isEqualTo(Duration.ofSeconds(2));
        assertThat(limiter.reserve()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a reservation is honoured when its time comes")
    void reservationBecomesDue() {
        var limiter = limiter(60);
        IntStream.range(0, 60).forEach(i -> limiter.tryAcquire());
        limiter.reserve();

        advance(Duration.ofSeconds(1));

        // The reserved token was already spent, so the bucket is at zero
        // rather than owing anything further.
        assertThat(limiter.limited()).isTrue();
    }

    @Test
    @DisplayName("await returns immediately while tokens are free")
    void awaitDoesNotBlockWhenFree() throws Exception {
        var limiter = limiter(60);

        limiter.await();

        assertThat(limiter.requestsPerMinute()).isEqualTo(60);
    }

    @Test
    @DisplayName("await is interruptible and restores the flag")
    void awaitIsInterruptible() throws Exception {
        // Cancellation is interruption: a shutdown must not be held up by a
        // limiter, and the flag must survive so the caller can unwind.
        var limiter = new RateLimiter(1, System::nanoTime);
        limiter.tryAcquire();

        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var done = new java.util.concurrent.CountDownLatch(1);
        var thread = Thread.ofVirtual().start(() -> {
            try {
                limiter.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                done.countDown();
            }
        });

        thread.interrupt();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    @DisplayName("reconfiguring refills to the new capacity in place")
    void reconfigure() {
        var limiter = limiter(2);
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.limited()).isTrue();

        // A raised limit is usable immediately; the limiter object is not
        // swapped, so callers already holding a reference keep working.
        limiter.reconfigure(10);

        assertThat(limiter.requestsPerMinute()).isEqualTo(10);
        assertThat(IntStream.range(0, 10).allMatch(i -> limiter.tryAcquire())).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("reconfiguring to zero lifts the limit entirely")
    void reconfigureToUnlimited() {
        var limiter = limiter(1);
        limiter.tryAcquire();
        assertThat(limiter.tryAcquire()).isFalse();

        limiter.reconfigure(0);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.limited()).isFalse();
    }

    @Test
    @DisplayName("a negative rate is clamped to unlimited rather than deadlocking")
    void negativeRateIsUnlimited() {
        var limiter = limiter(-5);

        assertThat(limiter.requestsPerMinute()).isZero();
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("a single-request-per-minute limiter admits exactly one per minute")
    void slowestUsefulRate() {
        var limiter = limiter(1);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire()).isFalse();

        advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire()).isTrue();
    }
}

package io.flowcatalyst.router.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/router.md` §2.11.
///
/// Time is injected rather than slept through: the whole point of the design
/// is what happens at a window boundary, and a test that waits for a real
/// clock can only assert the coarse cases.
class GroupFlushRegistryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final GroupFlushRegistry registry = new GroupFlushRegistry(clock);

    @Test
    @DisplayName("an unflushed group is never suppressed")
    void unflushedGroupPassesThrough() {
        assertThat(registry.suppressed("orders-1")).isFalse();
        assertThat(registry.suppressedUntil("orders-1")).isEmpty();
    }

    @Test
    @DisplayName("a flushed group is suppressed until its window lapses")
    void suppressedUntilWindowLapses() {
        registry.flush("orders-1", Duration.ofSeconds(60));

        assertThat(registry.suppressed("orders-1")).isTrue();

        clock.advance(Duration.ofSeconds(59));
        assertThat(registry.suppressed("orders-1")).isTrue();

        // Exactly at the expiry the window is over — the boundary is
        // exclusive, so the probe happens as early as it can.
        clock.advance(Duration.ofSeconds(1));
        assertThat(registry.suppressed("orders-1")).isFalse();
    }

    @Test
    @DisplayName("expiry evicts the entry, so the next message probes the target")
    void expiryEvicts() {
        registry.flush("orders-1", Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(31));

        assertThat(registry.suppressed("orders-1")).isFalse();
        assertThat(registry.stats().active()).isZero();
        // Self-healing: no resume protocol, the group is simply live again.
        assertThat(registry.suppressedUntil("orders-1")).isEmpty();
    }

    @Test
    @DisplayName("no window named means the default, not forever")
    void defaultWindow() {
        registry.flush("orders-1", null);
        assertThat(registry.suppressedUntil("orders-1"))
                .contains(clock.instant().plus(GroupFlushRegistry.DEFAULT_TTL));

        registry.clear("orders-1");
        registry.flush("orders-1", Duration.ZERO);
        assertThat(registry.suppressedUntil("orders-1"))
                .contains(clock.instant().plus(GroupFlushRegistry.DEFAULT_TTL));
    }

    @Test
    @DisplayName("a target cannot silence a group for longer than the cap")
    void windowIsCapped() {
        registry.flush("orders-1", Duration.ofHours(9));

        assertThat(registry.suppressedUntil("orders-1"))
                .contains(clock.instant().plus(GroupFlushRegistry.MAX_TTL));
    }

    @Test
    @DisplayName("re-flushing extends a window and never shortens it")
    void extendOnly() {
        registry.flush("orders-1", Duration.ofMinutes(4));
        var farExpiry = registry.suppressedUntil("orders-1").orElseThrow();

        // A probe landing mid-window must not be able to pull the expiry in.
        assertThat(registry.flush("orders-1", Duration.ofSeconds(5))).isFalse();
        assertThat(registry.suppressedUntil("orders-1")).contains(farExpiry);

        assertThat(registry.flush("orders-1", Duration.ofMinutes(5))).isTrue();
        assertThat(registry.suppressedUntil("orders-1").orElseThrow()).isAfter(farExpiry);
    }

    @Test
    @DisplayName("an ungrouped message is a no-op in both directions")
    void ungroupedIsNoOp() {
        // Otherwise "" would become a bucket that swallows every ungrouped
        // message in the pool.
        assertThat(registry.flush("", Duration.ofMinutes(1))).isFalse();
        assertThat(registry.flush(null, Duration.ofMinutes(1))).isFalse();
        assertThat(registry.suppressed("")).isFalse();
        assertThat(registry.suppressed(null)).isFalse();
        assertThat(registry.stats().active()).isZero();
    }

    @Test
    @DisplayName("groups suppress independently")
    void groupsAreIndependent() {
        registry.flush("orders-1", Duration.ofMinutes(1));

        assertThat(registry.suppressed("orders-1")).isTrue();
        assertThat(registry.suppressed("orders-2")).isFalse();
    }

    @Test
    @DisplayName("an operator can lift a suppression early")
    void operatorClear() {
        registry.flush("orders-1", Duration.ofMinutes(5));
        registry.clear("orders-1");

        assertThat(registry.suppressed("orders-1")).isFalse();
    }

    @Test
    @DisplayName("the read-only view neither counts nor evicts")
    void readOnlyViewHasNoSideEffects() {
        registry.flush("orders-1", Duration.ofSeconds(30));

        IntStream.range(0, 5).forEach(i -> registry.suppressedUntil("orders-1"));
        assertThat(registry.stats().suppressed()).isZero();

        clock.advance(Duration.ofSeconds(31));
        assertThat(registry.suppressedUntil("orders-1")).isEmpty();
        // Still present until a deciding read evicts it.
        assertThat(registry.stats().active()).isZero();
    }

    @Test
    @DisplayName("counters measure decisions and suppressed messages, not requests")
    void counters() {
        registry.flush("orders-1", Duration.ofMinutes(1));
        registry.flush("orders-1", Duration.ofSeconds(1)); // shortening: changes nothing
        registry.flush("orders-2", Duration.ofMinutes(1));

        registry.suppressed("orders-1");
        registry.suppressed("orders-1");
        registry.suppressed("orders-3"); // not flushed

        var stats = registry.stats();
        assertThat(stats.flushes()).isEqualTo(2);
        assertThat(stats.suppressed()).isEqualTo(2);
        assertThat(stats.active()).isEqualTo(2);
    }

    @Test
    @DisplayName("concurrent flushes on one group never lose the longest window")
    void concurrentFlushesKeepTheLongestWindow() throws Exception {
        // The Go holds a mutex across read-then-write. Here the map's compute
        // provides the same atomicity; this pins that it actually does.
        int threads = 32;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int seconds = i + 1;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (registry.flush("orders-1", Duration.ofSeconds(seconds))) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        // Whatever the interleaving, the surviving window is the longest one
        // requested — a lost update would leave a shorter expiry behind.
        assertThat(registry.suppressedUntil("orders-1"))
                .contains(clock.instant().plus(Duration.ofSeconds(threads)));
        assertThat(accepted.get()).isBetween(1, threads);
    }

    /// A clock the test moves by hand.
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

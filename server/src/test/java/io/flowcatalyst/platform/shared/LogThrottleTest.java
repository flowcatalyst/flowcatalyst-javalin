package io.flowcatalyst.platform.shared;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogThrottleTest {

    @Test
    void oneLinePerIntervalCarryingTheCountHeldBack() {
        var now = new AtomicLong(1_000);
        var throttle = new LogThrottle(Duration.ofNanos(100), now::get);

        assertThat(throttle.admit()).as("the first failure is logged").isEqualTo(OptionalLong.of(0));
        assertThat(throttle.admit()).isEmpty();
        assertThat(throttle.admit()).isEmpty();
        now.addAndGet(99);
        assertThat(throttle.admit()).as("still inside the interval").isEmpty();
        now.addAndGet(1);
        assertThat(throttle.admit()).as("the next line says three were held back").isEqualTo(OptionalLong.of(3));
    }
}

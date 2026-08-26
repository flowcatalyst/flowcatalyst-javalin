package io.flowcatalyst.router.traffic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Load-balancer registration (`docs/spec/router.md` §10.3).
class AlbTrafficTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FakeTargetGroup group = new FakeTargetGroup();

    /// A negligible poll interval: these assert *when* the loop stops, not
    /// how long it naps between asks.
    private AlbTraffic traffic(Duration drainTimeout) {
        return new AlbTraffic(
                new AlbTraffic.Config("10.0.0.7", 8080, drainTimeout, Duration.ofMillis(1)), group, clock);
    }

    @Test
    @DisplayName("registering puts the instance in the target group")
    void registerAddsTheTarget() {
        var traffic = traffic(Duration.ofSeconds(30));

        traffic.register();

        assertThat(group.registered).isTrue();
        assertThat(traffic.status().registered()).isTrue();
        assertThat(traffic.status().lastChange()).isPresent();
        assertThat(traffic.status().lastError()).isEmpty();
    }

    @Test
    @DisplayName("a failed registration is recorded, not thrown")
    void failedRegistrationDoesNotStopTheRouter() {
        // An instance that cannot take HTTP traffic can still drain its
        // queues, and that is the more important job.
        group.failing = true;
        var traffic = traffic(Duration.ofSeconds(30));

        traffic.register();

        assertThat(traffic.status().registered()).isFalse();
        assertThat(traffic.status().lastError()).isPresent();
    }

    @Test
    @DisplayName("deregistering waits for connections already in flight to finish")
    void deregisterWaitsForDrain() {
        // Removing a target stops NEW requests; the ones in flight keep
        // going. Exiting immediately would cut them off.
        group.drainingChecksRemaining.set(2);
        var traffic = traffic(Duration.ofMinutes(5));

        traffic.deregister();

        assertThat(group.registered).isFalse();
        assertThat(group.drainChecks.get()).as("polled until it stopped draining").isEqualTo(3);
    }

    @Test
    @DisplayName("a balancer that never stops draining does not hold the process open")
    void drainWaitIsBounded() {
        group.drainingForever = true;
        var traffic = traffic(Duration.ofSeconds(12));

        traffic.deregister();

        // Bounded by the deadline rather than the balancer's answer.
        assertThat(group.drainChecks.get()).isLessThanOrEqualTo(4);
        assertThat(group.registered).isFalse();
    }

    @Test
    @DisplayName("a failed deregister leaves us reporting registered, which is the safe direction")
    void failedDeregisterKeepsReportingRegistered() {
        // Believing we are out while the balancer still has us in is the
        // dangerous way round: a shutdown would proceed while requests
        // still arrive.
        var traffic = traffic(Duration.ofSeconds(30));
        traffic.register();
        group.failing = true;

        traffic.deregister();

        assertThat(traffic.status().registered()).as("still in, as far as we know").isTrue();
        assertThat(traffic.status().lastError()).isPresent();
    }

    @Test
    @DisplayName("an unqueryable drain state stops the polling rather than spinning")
    void unqueryableDrainStops() {
        // A balancer we cannot query will not answer differently in five
        // seconds; the deadline is the backstop either way.
        group.drainCheckFails = true;
        var traffic = traffic(Duration.ofMinutes(5));

        traffic.deregister();

        assertThat(group.drainChecks.get()).isOne();
        assertThat(traffic.status().lastError()).isPresent();
    }

    @Test
    @DisplayName("registered is cleared before the drain wait, not after")
    void registeredClearedBeforeWaiting() {
        // The balancer is sending no new requests from the moment the
        // deregister call returns; a five-minute drain should not leave us
        // reporting the wrong state for five minutes.
        group.drainingChecksRemaining.set(1);
        var traffic = traffic(Duration.ofMinutes(5));
        traffic.register();

        traffic.deregister();

        assertThat(traffic.status().registered()).isFalse();
    }

    @Test
    @DisplayName("a missing drain timeout falls back to the balancer's own default")
    void defaultDrainTimeout() {
        // So our wait and the balancer's deregistration delay agree, instead
        // of one expiring first for no reason.
        var config = new AlbTraffic.Config("10.0.0.7", 8080, null);

        assertThat(config.drainTimeout()).isEqualTo(AlbTraffic.DEFAULT_DRAIN_TIMEOUT);
        assertThat(new AlbTraffic.Config("10.0.0.7", 8080, Duration.ZERO).drainTimeout())
                .isEqualTo(AlbTraffic.DEFAULT_DRAIN_TIMEOUT);
    }

    @Test
    @DisplayName("an unusable config is rejected at construction")
    void invalidConfig() {
        assertThatThrownBy(() -> new AlbTraffic.Config("  ", 8080, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlbTraffic.Config("10.0.0.7", 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("disabled traffic management is a no-op that says so")
    void disabledIsANoOp() {
        Traffic.DISABLED.register();
        Traffic.DISABLED.deregister();

        assertThat(Traffic.DISABLED.status().enabled()).isFalse();
        assertThat(Traffic.DISABLED.status().registered()).isFalse();
    }

    /// A target group whose drain state the test drives.
    private static final class FakeTargetGroup implements TargetGroup {
        volatile boolean registered;
        volatile boolean failing;
        volatile boolean drainCheckFails;
        volatile boolean drainingForever;
        final AtomicInteger drainingChecksRemaining = new AtomicInteger();
        final AtomicInteger drainChecks = new AtomicInteger();

        @Override
        public void register(String targetId, int port) {
            failIfAsked();
            registered = true;
        }

        @Override
        public void deregister(String targetId, int port) {
            failIfAsked();
            registered = false;
        }

        @Override
        public String arn() {
            return "arn:aws:elasticloadbalancing:eu-west-1:1:targetgroup/fc/abc";
        }

        @Override
        public boolean draining(String targetId, int port) {
            drainChecks.incrementAndGet();
            if (drainCheckFails) {
                throw new IllegalStateException("cannot reach the balancer");
            }
            return drainingForever || drainingChecksRemaining.getAndDecrement() > 0;
        }

        private void failIfAsked() {
            if (failing) {
                throw new IllegalStateException("balancer unreachable");
            }
        }
    }

    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            // Advances on every read, so a drain loop reaches its deadline in
            // a handful of iterations instead of the test sleeping through
            // the production cadence.
            var current = now;
            now = now.plus(AlbTraffic.DRAIN_POLL_INTERVAL);
            return current;
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

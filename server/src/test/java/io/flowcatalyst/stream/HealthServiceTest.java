package io.flowcatalyst.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/// [HealthService] (stream spec §7): the `IsLive`/`IsReady` truth table,
/// including the empty registry, and the aggregate counts.
class HealthServiceTest {

    @Test
    @DisplayName("an empty registry is neither live nor ready")
    void emptyRegistryIsNeitherLiveNorReady() {
        var service = new HealthService();

        assertThat(service.isLive()).isFalse();
        assertThat(service.isReady()).isFalse();
        var aggregate = service.aggregate();
        assertThat(aggregate.healthy()).isFalse();
        assertThat(aggregate.totalStreams()).isZero();
        assertThat(aggregate.healthyStreams()).isZero();
        assertThat(aggregate.unhealthyStreams()).isZero();
        assertThat(aggregate.streams()).isEmpty();
    }

    @Test
    @DisplayName("live requires at least one running projector; ready requires every one healthy")
    void liveIsAnyReadyIsAll() throws Exception {
        var service = new HealthService();
        Health running = new Health("running");
        Health stopped = new Health("stopped");
        setRunning(running, true);
        setRunning(stopped, false);
        service.register(running);
        service.register(stopped);

        assertThat(service.isLive()).as("at least one running").isTrue();
        assertThat(service.isReady()).as("not every one is healthy").isFalse();

        var aggregate = service.aggregate();
        assertThat(aggregate.healthy()).isFalse();
        assertThat(aggregate.totalStreams()).isEqualTo(2);
        assertThat(aggregate.healthyStreams()).isEqualTo(1);
        assertThat(aggregate.unhealthyStreams()).isEqualTo(1);
    }

    @Test
    @DisplayName("every projector running -> live, ready, and the aggregate is healthy")
    void allRunningIsFullyHealthy() throws Exception {
        var service = new HealthService();
        Health a = new Health("a");
        Health b = new Health("b");
        setRunning(a, true);
        setRunning(b, true);
        service.register(a);
        service.register(b);

        assertThat(service.isLive()).isTrue();
        assertThat(service.isReady()).isTrue();
        var aggregate = service.aggregate();
        assertThat(aggregate.healthy()).isTrue();
        assertThat(aggregate.totalStreams()).isEqualTo(2);
        assertThat(aggregate.healthyStreams()).isEqualTo(2);
        assertThat(aggregate.unhealthyStreams()).isZero();
    }

    @Test
    @DisplayName("none running -> neither live nor ready, even though the registry is non-empty")
    void noneRunningIsNeitherLiveNorReady() throws Exception {
        var service = new HealthService();
        Health a = new Health("a");
        setRunning(a, false);
        service.register(a);

        assertThat(service.isLive()).isFalse();
        assertThat(service.isReady()).isFalse();
    }

    private static void setRunning(Health health, boolean running) throws Exception {
        Method m = Health.class.getDeclaredMethod("setRunning", boolean.class);
        m.setAccessible(true);
        m.invoke(health, running);
    }
}

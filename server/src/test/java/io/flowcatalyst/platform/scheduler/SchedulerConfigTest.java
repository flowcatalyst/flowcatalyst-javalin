package io.flowcatalyst.platform.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerConfigTest {

    @Test
    void defaultsAreTheSpecsValues() {
        var c = SchedulerConfig.DEFAULTS;
        assertThat(c.bufferCapacity()).isEqualTo(1000);
        assertThat(c.dispatchers()).isEqualTo(10);
        assertThat(c.batchSize()).isEqualTo(500);
        assertThat(c.laneBatch()).isEqualTo(100);
        assertThat(c.pollInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void overridesApplyAndUnsetOrNonPositiveMeansDefault() {
        var set = SchedulerConfig.of(250, 3, 40);
        assertThat(set.bufferCapacity()).isEqualTo(250);
        assertThat(set.dispatchers()).isEqualTo(3);
        assertThat(set.batchSize()).isEqualTo(40);
        assertThat(SchedulerConfig.of(0, 0, 0)).isEqualTo(SchedulerConfig.DEFAULTS);
        assertThat(SchedulerConfig.of(-5, -1, -9)).isEqualTo(SchedulerConfig.DEFAULTS);
        assertThat(SchedulerConfig.of(0, 2, 0).dispatchers()).isEqualTo(2);
    }

    @Test
    void aZeroSizeIsRefused() {
        assertThatThrownBy(() -> SchedulerConfig.DEFAULTS.withDispatchers(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchedulerConfig.DEFAULTS.withBufferCapacity(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

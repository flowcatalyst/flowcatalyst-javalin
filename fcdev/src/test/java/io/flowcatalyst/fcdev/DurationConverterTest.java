package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurationConverterTest {

    @Test
    void parsesGoDurations() {
        assertThat(DurationConverter.parse("20s")).isEqualTo(Duration.ofSeconds(20));
        assertThat(DurationConverter.parse("150ms")).isEqualTo(Duration.ofMillis(150));
        assertThat(DurationConverter.parse("1m30s")).isEqualTo(Duration.ofSeconds(90));
        assertThat(DurationConverter.parse("1.5s")).isEqualTo(Duration.ofMillis(1500));
        assertThat(DurationConverter.parse("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(DurationConverter.parse("0")).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> DurationConverter.parse("20")).hasMessageContaining("missing unit");
        assertThatThrownBy(() -> DurationConverter.parse("abc")).hasMessageContaining("invalid duration");
    }

    @Test
    void formatsLikeGo() {
        assertThat(DurationConverter.format(Duration.ofSeconds(20))).isEqualTo("20s");
        assertThat(DurationConverter.format(Duration.ofSeconds(90))).isEqualTo("1m30s");
        assertThat(DurationConverter.format(Duration.ofMillis(1500))).isEqualTo("1.5s");
        assertThat(DurationConverter.format(Duration.ofMillis(150))).isEqualTo("150ms");
        assertThat(DurationConverter.format(Duration.ofHours(1).plusSeconds(5))).isEqualTo("1h0m5s");
    }
}

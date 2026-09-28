package io.flowcatalyst.server.diagnostics;

import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JfrSettingsTest {

    @Test
    @DisplayName("unset: the entry point decides whether it runs; 30 min, 256 MB, the default profile")
    void defaults() {
        var production = JfrSettings.fromEnv(new EnvReader(Map.of()), true);
        var fcdev = JfrSettings.fromEnv(new EnvReader(Map.of()), false);

        assertThat(production.enabled()).isTrue();
        assertThat(fcdev.enabled()).isFalse();
        assertThat(production.maxAge()).isEqualTo(Duration.ofMinutes(30));
        assertThat(production.maxSizeBytes()).isEqualTo(256L * 1024 * 1024);
        assertThat(production.settings()).isEqualTo("default");
    }

    @Test
    @DisplayName("each variable overrides its default, and FC_JFR_ENABLED beats the entry point's")
    void overrides() {
        var s = JfrSettings.fromEnv(new EnvReader(Map.of(
                "FC_JFR_ENABLED", "false",
                "FC_JFR_MAX_AGE_MINUTES", "90",
                "FC_JFR_MAX_SIZE_MB", "64",
                "FC_JFR_SETTINGS", "profile")), true);

        assertThat(s.enabled()).isFalse();
        assertThat(s.maxAge()).isEqualTo(Duration.ofMinutes(90));
        assertThat(s.maxSizeBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(s.settings()).isEqualTo("profile");
    }

    @Test
    @DisplayName("a non-positive bound falls back to its default rather than an unbounded recording")
    void nonPositiveBoundsFallBack() {
        var s = JfrSettings.fromEnv(new EnvReader(Map.of("FC_JFR_MAX_AGE_MINUTES", "0", "FC_JFR_MAX_SIZE_MB", "-1")), true);

        assertThat(s.maxAge()).isEqualTo(JfrSettings.DEFAULT_MAX_AGE);
        assertThat(s.maxSizeBytes()).isEqualTo(JfrSettings.DEFAULT_MAX_SIZE_MB * 1024 * 1024);
    }

    @Test
    @DisplayName("a settings name the JDK does not know is a warning and no recording, never a failed boot")
    void badSettingsDoNotThrow() {
        var started = ContinuousRecording.start(
                new JfrSettings(true, Duration.ofMinutes(1), 1024 * 1024, "no-such-profile"));

        assertThat(started).isEmpty();
        assertThat(ContinuousRecording.running()).isEmpty();
    }
}

package io.flowcatalyst.server.diagnostics;

import io.flowcatalyst.server.EnvReader;

import java.time.Duration;
import java.util.Objects;

/// How the process-wide continuous flight recording runs (`docs/diagnostics.md`).
///
/// | Variable | Default | Meaning |
/// |---|---|---|
/// | `FC_JFR_ENABLED` | `true` for fc-server, `false` for fcdev | start the recording at boot |
/// | `FC_JFR_MAX_AGE_MINUTES` | 30 | how far back the recording keeps data |
/// | `FC_JFR_MAX_SIZE_MB` | 256 | disk the recording may use, whichever bound is hit first |
/// | `FC_JFR_SETTINGS` | `default` | `default` (~1% overhead), `profile` (~2%, more sampling) or a `.jfc` path |
///
/// The repository (where the chunks live) is the JVM's: `java.io.tmpdir`
/// unless `-XX:FlightRecorderOptions:repository=<dir>` says otherwise, since
/// the public API cannot set it.
///
/// @param enabled      whether to start the recording at all
/// @param maxAge       how far back data is kept
/// @param maxSizeBytes bytes of repository the recording may use
/// @param settings     a predefined configuration name or a `.jfc` path
public record JfrSettings(boolean enabled, Duration maxAge, long maxSizeBytes, String settings) {

    public static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(30);
    public static final long DEFAULT_MAX_SIZE_MB = 256;
    public static final String DEFAULT_SETTINGS = "default";

    public JfrSettings {
        Objects.requireNonNull(maxAge, "maxAge");
        Objects.requireNonNull(settings, "settings");
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("maxAge must be positive: " + maxAge);
        }
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("maxSizeBytes must be positive: " + maxSizeBytes);
        }
    }

    /// Reads the variables above. `enabledByDefault` is the entry point's
    /// answer when `FC_JFR_ENABLED` is unset: on for fc-server (production),
    /// off for fcdev. A non-positive age or size falls back to its default,
    /// like every other numeric knob in `Env`.
    public static JfrSettings fromEnv(EnvReader env, boolean enabledByDefault) {
        int minutes = env.integer("FC_JFR_MAX_AGE_MINUTES", (int) DEFAULT_MAX_AGE.toMinutes());
        long megabytes = env.longValue("FC_JFR_MAX_SIZE_MB", DEFAULT_MAX_SIZE_MB);
        String settings = env.or("FC_JFR_SETTINGS", DEFAULT_SETTINGS).strip();
        return new JfrSettings(
                env.bool("FC_JFR_ENABLED", enabledByDefault),
                minutes > 0 ? Duration.ofMinutes(minutes) : DEFAULT_MAX_AGE,
                (megabytes > 0 ? megabytes : DEFAULT_MAX_SIZE_MB) * 1024 * 1024,
                settings.isEmpty() ? DEFAULT_SETTINGS : settings);
    }
}

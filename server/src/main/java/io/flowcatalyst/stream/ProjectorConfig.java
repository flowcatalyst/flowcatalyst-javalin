package io.flowcatalyst.stream;

import java.time.Duration;
import java.util.Objects;

/// A projector's timing knobs (stream spec §2). `pollInterval`, `idleSleep`
/// and `errorSleep` are the three fixed durations [Projector#nextSleep]
/// chooses between; `batchSize` is both the claim's `LIMIT` and the
/// threshold [Projector#nextSleep] compares the step's return value against
/// to decide whether more work is very likely still waiting.
///
/// @param enabled   whether the projector's loop does anything at all; a
///                  disabled projector's [Health#running] is never set true
/// @param batchSize the claim batch size (per-projector default, or
///                  `FC_STREAM_<NAME>_BATCH_SIZE` / `FC_STREAM_BATCH_SIZE`)
/// @param pollInterval sleep after a partial batch (`0 < n < batchSize`)
/// @param idleSleep    sleep after an empty batch (`n == 0`)
/// @param errorSleep   sleep after a step error
public record ProjectorConfig(boolean enabled, int batchSize, Duration pollInterval, Duration idleSleep,
                               Duration errorSleep) {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);
    public static final Duration DEFAULT_IDLE_SLEEP = Duration.ofSeconds(1);
    public static final Duration DEFAULT_ERROR_SLEEP = Duration.ofSeconds(5);

    public ProjectorConfig {
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(idleSleep, "idleSleep");
        Objects.requireNonNull(errorSleep, "errorSleep");
    }

    /// `enabled` with `batchSize`, at the stream spec §2 default pacing.
    public static ProjectorConfig of(boolean enabled, int batchSize) {
        return new ProjectorConfig(enabled, batchSize, DEFAULT_POLL_INTERVAL, DEFAULT_IDLE_SLEEP, DEFAULT_ERROR_SLEEP);
    }
}

package io.flowcatalyst.platform.scheduler;

import java.time.Duration;

/// The dispatch scheduler's sizing knobs (`docs/spec/dispatch-seam.md` §3
/// timing table). Defaults are identical in all three implementations.
///
/// @param bufferCapacity the most jobs claimed and not yet settled at any
///                       moment — the permit pool that bounds how far the
///                       poller runs ahead of the dispatchers
/// @param dispatchers    the number of lanes (independent publishing workers)
/// @param batchSize      the most rows one claim returns
/// @param laneBatch      the most jobs one lane takes from its channel to
///                       publish and mark `QUEUED` together
/// @param pollInterval   how long the poller waits when it has nothing to do,
///                       or when it should back off
public record SchedulerConfig(int bufferCapacity, int dispatchers, int batchSize, int laneBatch,
                              Duration pollInterval) {

    public static final int DEFAULT_BUFFER_CAPACITY = 1000;
    public static final int DEFAULT_DISPATCHERS = 10;
    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final int DEFAULT_LANE_BATCH = 100;
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    public static final SchedulerConfig DEFAULTS = new SchedulerConfig(DEFAULT_BUFFER_CAPACITY,
            DEFAULT_DISPATCHERS, DEFAULT_BATCH_SIZE, DEFAULT_LANE_BATCH, DEFAULT_POLL_INTERVAL);

    public SchedulerConfig {
        if (bufferCapacity < 1) throw new IllegalArgumentException("bufferCapacity must be >= 1");
        if (dispatchers < 1) throw new IllegalArgumentException("dispatchers must be >= 1");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        if (laneBatch < 1) throw new IllegalArgumentException("laneBatch must be >= 1");
        if (pollInterval == null || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be >= 0");
        }
    }

    /// The defaults with the three operator overrides applied; a value `<= 0`
    /// means "not set" (`Env` reads an unset variable as `0`).
    public static SchedulerConfig of(int bufferCapacity, int dispatchers, int batchSize) {
        return new SchedulerConfig(
                bufferCapacity > 0 ? bufferCapacity : DEFAULT_BUFFER_CAPACITY,
                dispatchers > 0 ? dispatchers : DEFAULT_DISPATCHERS,
                batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE,
                DEFAULT_LANE_BATCH, DEFAULT_POLL_INTERVAL);
    }

    public SchedulerConfig withBatchSize(int batchSize) {
        return new SchedulerConfig(bufferCapacity, dispatchers, batchSize, laneBatch, pollInterval);
    }

    public SchedulerConfig withBufferCapacity(int bufferCapacity) {
        return new SchedulerConfig(bufferCapacity, dispatchers, batchSize, laneBatch, pollInterval);
    }

    public SchedulerConfig withDispatchers(int dispatchers) {
        return new SchedulerConfig(bufferCapacity, dispatchers, batchSize, laneBatch, pollInterval);
    }

    public SchedulerConfig withLaneBatch(int laneBatch) {
        return new SchedulerConfig(bufferCapacity, dispatchers, batchSize, laneBatch, pollInterval);
    }

    public SchedulerConfig withPollInterval(Duration pollInterval) {
        return new SchedulerConfig(bufferCapacity, dispatchers, batchSize, laneBatch, pollInterval);
    }
}

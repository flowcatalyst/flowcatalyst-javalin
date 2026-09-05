package io.flowcatalyst.stream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/// One projector's liveness/throughput counters (stream spec §7): whether
/// its loop is currently running, how many rows it has processed in total,
/// how many step errors it has seen, and when it last successfully
/// processed a non-empty batch. Thread-safe: [Projector] updates it from its
/// own virtual thread while [HealthService]/an HTTP handler reads it from
/// another.
///
/// `healthy == running` today (stream spec §7, D5): a projector wedged in
/// `ErrorSleep` forever still reports healthy. That is the documented Go
/// behaviour, not an oversight — [#isHealthy] is the one seam a future
/// "degrade on stale poll / error rate" rule would change.
public final class Health {

    private final String name;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong batchSequence = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong lastPollTimeMs = new AtomicLong();

    public Health(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() {
        return name;
    }

    void setRunning(boolean value) {
        running.set(value);
    }

    public boolean isRunning() {
        return running.get();
    }

    /// `healthy == running` (spec §7, D5).
    public boolean isHealthy() {
        return isRunning();
    }

    /// Adds `n` to the running total and stamps [#lastPollTimeMs]. Called
    /// only when `n > 0` (spec §2's `AddProcessed`).
    void addProcessed(long n) {
        batchSequence.addAndGet(n);
        lastPollTimeMs.set(System.currentTimeMillis());
    }

    void recordError() {
        errorCount.incrementAndGet();
    }

    public long batchSequence() {
        return batchSequence.get();
    }

    public long errorCount() {
        return errorCount.get();
    }

    public long lastPollTimeMs() {
        return lastPollTimeMs.get();
    }

    /// The stream spec §7 wire shape: `{name, status, running, healthy,
    /// batchSequence, errorCount, lastPollTimeMs}`.
    public Snapshot snapshot() {
        boolean r = isRunning();
        return new Snapshot(name, r ? "RUNNING" : "STOPPED", r, isHealthy(), batchSequence(), errorCount(),
                lastPollTimeMs());
    }

    public record Snapshot(String name, String status, boolean running, boolean healthy, long batchSequence,
                            long errorCount, long lastPollTimeMs) {
    }
}

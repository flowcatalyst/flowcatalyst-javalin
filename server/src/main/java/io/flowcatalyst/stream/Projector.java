package io.flowcatalyst.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/// The generic projector loop (stream spec §2): a leader-gated,
/// self-pacing `while` loop over one [Step]. Every one of the four
/// projectors (`event_fan_out`, `event_projection`, `dispatch_job_projection`)
/// is exactly this loop over a different [Step]; the partition manager is
/// tick-based (§6) and does not use this class.
///
/// Cancellation is interruption (CONVENTIONS §8): [#run] is meant to be the
/// body of a virtual thread inside a `StructuredTaskScope`
/// ([StreamProcessor]); interrupting that thread stops the loop at its next
/// check, restoring the interrupt flag rather than swallowing it.
public final class Projector implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Projector.class);

    /// One claim-and-act batch, returning how many rows it processed. Thrown
    /// exceptions are the step's error outcome (spec §2 `err`): logged,
    /// counted on [Health#recordError], and paced with [#errorSleep()].
    @FunctionalInterface
    public interface Step {
        int step(int batchSize) throws Exception;
    }

    private final String name;
    private final ProjectorConfig config;
    private final Step step;
    private final BooleanSupplier leader;
    private final Health health;

    public Projector(String name, ProjectorConfig config, Step step, BooleanSupplier leader, Health health) {
        this.name = Objects.requireNonNull(name, "name");
        this.config = Objects.requireNonNull(config, "config");
        this.step = Objects.requireNonNull(step, "step");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.health = Objects.requireNonNull(health, "health");
    }

    /// The four-tier pacing table (stream spec §2, pinned by Go's
    /// `TestNextSleep_AdaptiveTiers`), in precedence order: an error always
    /// wins, then an empty batch, then a full-or-over batch (poll again
    /// immediately — more work is very likely still waiting), else the
    /// steady-state poll interval.
    static Duration nextSleep(ProjectorConfig config, boolean errored, int n) {
        if (errored) return config.errorSleep();
        if (n == 0) return config.idleSleep();
        if (n >= config.batchSize()) return Duration.ZERO;
        return config.pollInterval();
    }

    @Override
    public void run() {
        if (!config.enabled()) {
            return; // a disabled projector never sets Health#running (spec §2)
        }
        health.setRunning(true);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (!leader.getAsBoolean()) {
                    if (!sleep(config.idleSleep())) return;
                    continue;
                }
                boolean errored = false;
                int n = 0;
                try {
                    n = step.step(config.batchSize());
                } catch (Exception e) {
                    errored = true;
                    LOG.warn("projector step error name={}", name, e);
                    health.recordError();
                }
                if (n > 0) {
                    health.addProcessed(n);
                }
                Duration sleep = nextSleep(config, errored, n);
                if (!sleep.isZero() && !sleep(sleep)) return;
            }
        } finally {
            health.setRunning(false);
            LOG.info("projector stopped name={}", name);
        }
    }

    /// `true` if the sleep completed; `false` if it was interrupted, having
    /// already restored the interrupt flag (never swallowed).
    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

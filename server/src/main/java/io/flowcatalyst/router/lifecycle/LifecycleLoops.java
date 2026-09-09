package io.flowcatalyst.router.lifecycle;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.observability.Warnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// The router's periodic housekeeping, on one scheduler
/// (`docs/spec/router.md` §4.6, §9.3, constants 33, 35, 47).
///
/// ### Why these run together
///
/// Each is independent, cheap, and useless if it stops. Running them as one
/// set means there is a single thing to start and stop and a single place to
/// see whether housekeeping is alive — rather than four loops that can each
/// die quietly and leave a different symptom.
///
/// ### Why a failing task never stops its loop
///
/// A sweep that throws is a bug in the sweep, not a reason to stop
/// housekeeping for the life of the process. The failure is logged and the
/// next tick runs. The opposite — one bad tick silencing the stall detector
/// forever — is exactly the kind of quiet degradation these loops exist to
/// catch.
public final class LifecycleLoops implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LifecycleLoops.class);

    /// How often stalled messages are looked for (constant 40).
    public static final Duration STALL_CHECK = Duration.ofSeconds(60);

    /// How often abandoned tracker entries are reaped (constant 33).
    public static final Duration REAP_INTERVAL = Duration.ofMinutes(5);

    /// How long a tracker entry may go untouched before it is reaped.
    public static final Duration REAP_MAX_AGE = Duration.ofMinutes(15);

    /// How often broker depths are sampled (constant 42).
    public static final Duration BROKER_REFRESH = Duration.ofSeconds(60);

    /// How often [io.flowcatalyst.router.observability.WarningStore#cleanup]
    /// runs (spec §7.1, A-08). `WarningStore.cleanup()` was built and tested
    /// on 2026-08-25 and left with no caller — every "an unacked warning
    /// auto-acks after an hour" / "an INFO entry ages out after an hour"
    /// guarantee held only in a unit test until this task exists.
    public static final Duration WARNING_CLEANUP_INTERVAL = Duration.ofMinutes(1);

    /// Tracker size past which memory is worth a warning (constant 35).
    public static final int IN_FLIGHT_WARN_THRESHOLD = 10_000;

    /// How often idle synthesised `{client}-DEFAULT-POOL` pools are swept
    /// for eviction (R-59, `docs/spec/router-completion.md` unit 3).
    public static final Duration SYNTH_POOL_EVICT_INTERVAL = Duration.ofMinutes(1);

    /// How often a drained pool is closed and a lingering consumer is
    /// retired (X-11/R-26, `docs/spec/router-completion.md` §2 rulings 5
    /// and 6) — the same cadence as the stall detector, since both are
    /// "has this finished emptying yet" sweeps of the same shape.
    public static final Duration DRAIN_CHECK = Duration.ofSeconds(60);

    /// One periodic task. Named so a log line says which loop misbehaved.
    public record Task(String name, Duration interval, Runnable action) {
    }

    private final List<Thread> threads = new CopyOnWriteArrayList<>();

    /// Starts every task on its own virtual thread.
    ///
    /// Interruption is the stop signal, as everywhere else — [#close]
    /// interrupts them and they unwind at their next sleep.
    public void start(List<Task> tasks) {
        tasks.forEach(task -> threads.add(
                Thread.ofVirtual().name("router-" + task.name()).start(() -> run(task))));
        log.atInfo().setMessage("router housekeeping started")
                .addKeyValue("tasks", tasks.stream().map(Task::name).toList())
                .log();
    }

    private void run(Task task) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(task.interval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                task.action().run();
            } catch (RuntimeException e) {
                // Logged and survived: one bad tick must not silence
                // housekeeping for the life of the process.
                log.atWarn().setMessage("router housekeeping task failed; continuing")
                        .addKeyValue("name", task.name())
                        .setCause(e)
                        .log();
            }
        }
    }

    /// The standard set: find stalls, reap abandoned entries, sample broker
    /// depths, warn if the tracker is growing without bound, and sweep the
    /// warning store (A-08).
    ///
    /// `cleanupWarnings`, `evictSynthPools`, `closeDrainedPools` and
    /// `retireLingeringConsumers` are `Runnable`s — typically
    /// `WarningStore::cleanup`, `manager::evictIdleSynthesisedPools` partially
    /// applied to its TTL, `manager::closeDrainedPools` and
    /// `manager::retireLingeringConsumers` — rather than the objects they act
    /// on, the same shape as `refreshBrokerStats`: this loop only ever needs
    /// to invoke the sweep, never to read the thing being swept, so it
    /// depends on nothing it does not use.
    public static List<Task> standard(StallDetector stalls, InFlightTracker tracker,
                                      Warnings warnings, Runnable refreshBrokerStats,
                                      Runnable cleanupWarnings, Runnable evictSynthPools,
                                      Runnable closeDrainedPools, Runnable retireLingeringConsumers) {
        return List.of(
                new Task("stall-detector", STALL_CHECK, stalls::sweep),
                new Task("reaper", REAP_INTERVAL, () -> reap(tracker, warnings)),
                new Task("broker-stats", BROKER_REFRESH, refreshBrokerStats),
                new Task("warning-cleanup", WARNING_CLEANUP_INTERVAL, cleanupWarnings),
                new Task("synth-pool-evict", SYNTH_POOL_EVICT_INTERVAL, evictSynthPools),
                // X-11/R-26: a removed pool or a removed/changed queue drains
                // or lingers in the background rather than aborting on the
                // spot (`docs/spec/router-completion.md` §2 rulings 5, 6) —
                // these two sweeps are what actually releases them once
                // nothing references them any more.
                new Task("pool-drain-close", DRAIN_CHECK, closeDrainedPools),
                new Task("consumer-linger-retire", DRAIN_CHECK, retireLingeringConsumers));
    }

    /// Drops tracker entries nothing has touched, and warns when the tracker
    /// is large enough to be worth noticing.
    ///
    /// The reaper is a **backstop against a backend that loses a message
    /// without telling us**, not a correctness mechanism — every normal path
    /// releases its own entry. So a reap that finds anything is itself worth
    /// logging: it means something upstream did not clean up after itself.
    static void reap(InFlightTracker tracker, Warnings warnings) {
        int reaped = tracker.reapIdle(REAP_MAX_AGE);
        if (reaped > 0) {
            log.atWarn().setMessage("reaped idle in-flight entries")
                    .addKeyValue("count", reaped)
                    .addKeyValue("max_age", REAP_MAX_AGE)
                    .log();
        }
        int size = tracker.size();
        if (size >= IN_FLIGHT_WARN_THRESHOLD) {
            // Growth past this is usually a broker redelivering faster than
            // deliveries complete; it ends in memory pressure long before it
            // ends in anything else.
            warnings.raise(Warnings.Severity.ERROR, "RESOURCE",
                    "in-flight tracker holds " + size + " messages");
        }
    }

    @Override
    public void close() {
        threads.forEach(Thread::interrupt);
        threads.clear();
        log.info("router housekeeping stopped");
    }
}

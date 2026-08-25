package io.flowcatalyst.router.lifecycle;

import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.manager.Warnings;
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

    /// Tracker size past which memory is worth a warning (constant 35).
    public static final int IN_FLIGHT_WARN_THRESHOLD = 10_000;

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
        log.info("router housekeeping started: {}", tasks.stream().map(Task::name).toList());
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
                log.warn("router housekeeping task {} failed; continuing", task.name(), e);
            }
        }
    }

    /// The standard set: find stalls, reap abandoned entries, sample broker
    /// depths, and warn if the tracker is growing without bound.
    public static List<Task> standard(StallDetector stalls, InFlightTracker tracker,
                                      Warnings warnings, Runnable refreshBrokerStats) {
        return List.of(
                new Task("stall-detector", STALL_CHECK, stalls::sweep),
                new Task("reaper", REAP_INTERVAL, () -> reap(tracker, warnings)),
                new Task("broker-stats", BROKER_REFRESH, refreshBrokerStats));
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
            log.warn("reaped {} in-flight entries idle for over {}", reaped, REAP_MAX_AGE);
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

package io.flowcatalyst.testjfr;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Runs a piece of work under a real flight recording and hands back what it
/// actually recorded.
///
/// Reading the events back out of a dumped recording is the whole point: an
/// event class that compiles, and even a `commit()` that runs, prove nothing
/// about whether the fields survive JFR's own registration and serialisation.
/// A misspelled `@Name`, a field type JFR will not persist, or an event never
/// enabled all look exactly like working code from inside the process.
///
/// Shared across every FlowCatalyst subsystem with a flight-recorder event
/// (`docs/spec/jfr-events.md` §6) — one place lists every event class that
/// exists, so a test enabling exactly one can be sure every other one is
/// actually off rather than merely unmentioned.
public final class Recorded {

    private Recorded() {
    }

    /// Every FlowCatalyst event class known to this build. A custom event is
    /// enabled by default, so "I did not enable it" is not "it is off" —
    /// each of these is explicitly disabled before the one under test is
    /// explicitly enabled.
    private static final List<Class<? extends jdk.jfr.Event>> ALL_EVENTS = List.of(
            io.flowcatalyst.router.observability.jfr.MessageSettledEvent.class,
            io.flowcatalyst.router.observability.jfr.GroupDecisionEvent.class,
            io.flowcatalyst.router.observability.jfr.DispatchEvent.class,
            io.flowcatalyst.platform.scheduler.jfr.ClaimedBatchEvent.class,
            io.flowcatalyst.platform.dispatchjob.jfr.DispatchProcessedEvent.class,
            io.flowcatalyst.stream.jfr.FanOutBatchEvent.class,
            io.flowcatalyst.outbox.jfr.OutboxItemSettledEvent.class,
            io.flowcatalyst.platform.scheduler.jobs.jfr.JobFiredEvent.class,
            io.flowcatalyst.platform.purger.jfr.PurgerStepEvent.class,
            io.flowcatalyst.server.dbsecret.jfr.DbSecretRefreshEvent.class);

    public static List<RecordedEvent> from(Class<? extends jdk.jfr.Event> event, Runnable work) throws Exception {
        Path dump = Files.createTempFile("flowcatalyst-events", ".jfr");
        try (var recording = new Recording()) {
            ALL_EVENTS.forEach(recording::disable);
            recording.enable(event).withoutThreshold().withoutStackTrace();
            recording.start();
            work.run();
            recording.stop();
            recording.dump(dump);
            return RecordingFile.readAllEvents(dump);
        } finally {
            Files.deleteIfExists(dump);
        }
    }
}

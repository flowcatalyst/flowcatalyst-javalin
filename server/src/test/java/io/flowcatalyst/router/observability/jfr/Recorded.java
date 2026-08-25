package io.flowcatalyst.router.observability.jfr;

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
public final class Recorded {

    private Recorded() {
    }

    /// Every router event, so a test can enable exactly one and be sure the
    /// others are off rather than merely unmentioned. A custom event is
    /// enabled by default, so "I did not enable it" is not "it is off".
    private static final List<Class<? extends jdk.jfr.Event>> ROUTER_EVENTS = List.of(
            MessageSettledEvent.class, GroupDecisionEvent.class, DispatchEvent.class);

    public static List<RecordedEvent> from(Class<? extends jdk.jfr.Event> event, Runnable work) throws Exception {
        Path dump = Files.createTempFile("router-events", ".jfr");
        try (var recording = new Recording()) {
            ROUTER_EVENTS.forEach(recording::disable);
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

package io.flowcatalyst.server.diagnostics;

import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Optional;

/// The process-wide flight recording that runs from boot, so that when
/// something goes wrong the last half hour is already on disk
/// (`docs/diagnostics.md`).
///
/// A bounded, rolling, to-disk recording: the JDK's `default` (or `profile`)
/// settings plus every FlowCatalyst event (`io.flowcatalyst.*`: dispatch
/// attempts, settlements with their reasons, ordered-group decisions, the
/// scheduler and stream batches), which are enabled by default. Chunks older
/// than [JfrSettings#maxAge] or beyond [JfrSettings#maxSizeBytes] are
/// discarded, so it never grows; nothing is written anywhere else until an
/// operator asks for a snapshot ([Diagnostics#jfrSnapshot]).
///
/// ### What it deliberately does not record
///
/// `jdk.InitialEnvironmentVariable` and `jdk.InitialSystemProperty` are off.
/// Both are on in the JDK's own settings and both would copy the process
/// environment into every recording — `FC_DATABASE_URL`, `FC_APP_KEY`, AWS
/// credentials — and a recording is a file an operator downloads.
///
/// ### Where JFR is missing
///
/// A native image built without `--enable-monitoring=jfr` has no flight
/// recorder; [#start] then logs once and answers empty, and the diagnostics
/// routes say so rather than fail.
public final class ContinuousRecording implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuousRecording.class);

    /// The recording's name — how [Diagnostics] and `jcmd JFR.check` find it.
    public static final String NAME = "flowcatalyst-continuous";

    /// Events whose payload is the process environment or its system
    /// properties — see the class doc.
    static final String[] SECRET_BEARING_EVENTS = {"jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty"};

    private final Recording recording;

    private ContinuousRecording(Recording recording) {
        this.recording = recording;
    }

    /// Starts the recording, or explains in the log why not. Never throws: a
    /// diagnostic that cannot start must not stop the server starting.
    public static Optional<ContinuousRecording> start(JfrSettings settings) {
        if (!settings.enabled()) {
            LOG.atInfo().setMessage("continuous flight recording off (FC_JFR_ENABLED=false)").log();
            return Optional.empty();
        }
        if (!available()) {
            LOG.atWarn().setMessage("continuous flight recording unavailable: this runtime has no flight recorder "
                    + "(a native image needs --enable-monitoring=jfr)").log();
            return Optional.empty();
        }
        Recording recording = null;
        try {
            recording = new Recording(configuration(settings.settings()));
            recording.setName(NAME);
            recording.setToDisk(true);
            recording.setMaxAge(settings.maxAge());
            recording.setMaxSize(settings.maxSizeBytes());
            for (String event : SECRET_BEARING_EVENTS) {
                recording.disable(event);
            }
            recording.start();
            LOG.atInfo().setMessage("continuous flight recording started")
                    .addKeyValue("settings", settings.settings())
                    .addKeyValue("max_age", settings.maxAge())
                    .addKeyValue("max_size_mb", settings.maxSizeBytes() / (1024 * 1024))
                    .log();
            return Optional.of(new ContinuousRecording(recording));
        } catch (IOException | ParseException | RuntimeException e) {
            // A bad FC_JFR_SETTINGS, an unwritable repository, a runtime whose
            // recorder refuses: a missing diagnostic, not a failed boot.
            if (recording != null) {
                recording.close();
            }
            LOG.atWarn().setMessage("continuous flight recording could not start; continuing without it")
                    .addKeyValue("settings", settings.settings())
                    .setCause(e)
                    .log();
            return Optional.empty();
        }
    }

    /// Whether this runtime has a flight recorder at all. `false` in a native
    /// image built without JFR support, where touching the API throws.
    public static boolean available() {
        try {
            return FlightRecorder.isAvailable();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /// The continuous recording, if one is running in this process — found by
    /// [#NAME] rather than held, so the diagnostics routes need no reference
    /// to whoever started it.
    public static Optional<Recording> running() {
        if (!available() || !FlightRecorder.isInitialized()) {
            return Optional.empty();
        }
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(r -> NAME.equals(r.getName()) && r.getState() == RecordingState.RUNNING)
                .findFirst();
    }

    private static Configuration configuration(String settings) throws IOException, ParseException {
        if (settings.endsWith(".jfc")) {
            return Configuration.create(Path.of(settings));
        }
        return Configuration.getConfiguration(settings);
    }

    /// Stops and discards the recording. The chunks it kept are deleted with
    /// it; there is nothing to flush.
    @Override
    public void close() {
        recording.close();
    }
}

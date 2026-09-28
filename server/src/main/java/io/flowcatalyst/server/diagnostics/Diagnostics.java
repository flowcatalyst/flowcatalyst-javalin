package io.flowcatalyst.server.diagnostics;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.flowcatalyst.platform.shared.json.Json;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The three things an operator can pull out of a running process
/// (`docs/diagnostics.md`): a thread dump that includes virtual threads, a
/// slice of the continuous flight recording, and the FlowCatalyst events in
/// that slice for one message or group. [DiagnosticsRoutes] serves them; this
/// class knows nothing about HTTP.
///
/// Each produces a file in its own temp directory. [DumpFile#open] streams it
/// and deletes it once the stream is closed, so nothing accumulates in the
/// container's temp space however a download ends.
public final class Diagnostics {

    /// FlowCatalyst's own events: `@Name("io.flowcatalyst.…")` on every one.
    static final String EVENT_PREFIX = "io.flowcatalyst.";

    /// Field names that carry a message's identity, across the router's
    /// events (`messageId`) and the platform's (`jobId`: a dispatch job's id
    /// *is* the router message id — dispatch-seam spec §2).
    static final Set<String> MESSAGE_ID_FIELDS = Set.of("messageId", "jobId");

    /// Field names that carry an ordered group.
    static final Set<String> GROUP_FIELDS = Set.of("group", "messageGroup");

    /// Most events one query returns. A message's life is a handful of
    /// events; a busy group's can be thousands, and the answer is a page an
    /// operator reads, not an export — the snapshot is the export.
    public static final int MAX_EVENTS = 1000;

    private Diagnostics() {
    }

    /// A file to stream once and then forget.
    ///
    /// @param file        what to send
    /// @param dir         the temp directory holding it, deleted with it
    /// @param contentType its media type
    /// @param filename    the download name, for `Content-Disposition`
    public record DumpFile(Path file, Path dir, String contentType, String filename) {

        public long size() {
            try {
                return Files.size(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /// A stream over the file that deletes it — and its directory — when
        /// closed, then runs `onClose`.
        public InputStream open(Runnable onClose) throws IOException {
            return new FilterInputStream(Files.newInputStream(file)) {
                private boolean closed;

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    try {
                        super.close();
                    } finally {
                        delete();
                        onClose.run();
                    }
                }
            };
        }

        /// Deletes the file and its directory, quietly.
        public void delete() {
            deleteQuietly(file);
            deleteQuietly(dir);
        }
    }

    // ── thread dump ─────────────────────────────────────────────────────────

    /// Every thread, **virtual threads included**, as the JDK's own JSON
    /// thread dump (`HotSpotDiagnosticMXBean#dumpThreads`, the format `jcmd
    /// Thread.dump_to_file -format=json` writes). A virtual thread is only
    /// visible this way: `Thread.getAllStackTraces()` and the older
    /// `jstack`-style dump list platform threads alone, and with one virtual
    /// thread per request and per message, platform threads are not where
    /// anything is.
    ///
    /// Where the bean cannot dump (a native image) this falls back to
    /// [#platformThreadsDump], which says in its body that virtual threads
    /// are missing.
    public static DumpFile threadDump() throws IOException {
        Path dir = Files.createTempDirectory("fc-threads-");
        Path file = dir.resolve("threads.json");
        try {
            var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (bean == null) {
                throw new UnsupportedOperationException("no HotSpotDiagnosticMXBean in this runtime");
            }
            // The path must be absolute and must not exist yet.
            bean.dumpThreads(file.toAbsolutePath().toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
        } catch (UnsupportedOperationException | IllegalArgumentException | LinkageError e) {
            deleteQuietly(file);
            Files.writeString(file, platformThreadsDump(e.toString()));
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            deleteQuietly(dir);
            throw e;
        }
        return new DumpFile(file, dir, "application/json", "threads.json");
    }

    /// The fallback dump: platform threads from `Thread.getAllStackTraces()`,
    /// shaped like the JDK's JSON dump so one reader handles both, with
    /// `virtualThreadsIncluded: false` and the reason the full dump was not
    /// available.
    static String platformThreadsDump(String reason) {
        List<Map<String, Object>> threads = new ArrayList<>();
        Thread.getAllStackTraces().entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getKey().threadId()))
                .forEach(e -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("tid", Long.toString(e.getKey().threadId()));
                    t.put("name", e.getKey().getName());
                    t.put("state", e.getKey().getState().name());
                    t.put("stack", java.util.Arrays.stream(e.getValue()).map(StackTraceElement::toString).toList());
                    threads.add(t);
                });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("container", "<root>");
        root.put("threads", threads);
        root.put("threadCount", Integer.toString(threads.size()));
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("processId", Long.toString(ProcessHandle.current().pid()));
        dump.put("time", Instant.now().toString());
        dump.put("runtimeVersion", Runtime.version().toString());
        dump.put("virtualThreadsIncluded", false);
        dump.put("reason", reason);
        dump.put("threadContainers", List.of(root));
        return Json.MAPPER.writeValueAsString(Map.of("threadDump", dump));
    }

    // ── flight recording ────────────────────────────────────────────────────

    /// The last `last` of the continuous recording, as a `.jfr` file for JDK
    /// Mission Control or `jfr print`. Empty when no continuous recording is
    /// running ([ContinuousRecording#running]) or it holds no data yet.
    ///
    /// `takeSnapshot` is the JDK's own "copy what the recorder has" (the
    /// running recording is untouched), and `setMaxAge` on the snapshot
    /// trims it to the window asked for — the pattern its javadoc gives.
    public static Optional<DumpFile> jfrSnapshot(Duration last) throws IOException {
        if (ContinuousRecording.running().isEmpty()) {
            return Optional.empty();
        }
        Path dir = Files.createTempDirectory("fc-jfr-");
        Path file = dir.resolve("flowcatalyst.jfr");
        try (Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot()) {
            if (snapshot.getSize() == 0) {
                deleteQuietly(dir);
                return Optional.empty();
            }
            snapshot.setMaxAge(last);
            snapshot.dump(file);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            deleteQuietly(dir);
            throw e;
        }
        String name = "flowcatalyst-" + ProcessHandle.current().pid() + "-"
                + Instant.now().toString().replace(":", "") + ".jfr";
        return Optional.of(new DumpFile(file, dir, "application/octet-stream", name));
    }

    /// What to look for in [#events].
    ///
    /// @param messageId matches `messageId`/`jobId`; `null` = any
    /// @param group     matches `group`/`messageGroup`; `null` = any
    /// @param last      how far back to look
    public record EventQuery(String messageId, String group, Duration last) {

        public EventQuery {
            if (blank(messageId) && blank(group)) {
                throw new IllegalArgumentException("messageId or group is required");
            }
        }

        boolean matches(RecordedEvent event) {
            return (blank(messageId) || anyFieldEquals(event, MESSAGE_ID_FIELDS, messageId))
                    && (blank(group) || anyFieldEquals(event, GROUP_FIELDS, group));
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    /// One FlowCatalyst event, flattened for JSON.
    ///
    /// @param fields the event's own fields (`messageId`, `outcome`,
    ///               `reason`, …) — the JDK's `startTime`, `duration`,
    ///               `eventThread` and `stackTrace` are lifted out or left out
    public record EventSummary(Instant time, String type, String label, long durationMillis, String thread,
                               Map<String, Object> fields) {

        public EventSummary {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /// Answers "what happened to message X" (or group G) without opening a
    /// recording in Mission Control: the FlowCatalyst events in the last
    /// `query.last()` whose id or group matches, oldest first, at most
    /// [#MAX_EVENTS] (the newest ones are kept when there are more). Empty
    /// when no continuous recording is running.
    public static Optional<List<EventSummary>> events(EventQuery query) throws IOException {
        var snapshot = jfrSnapshot(query.last());
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        var dump = snapshot.get();
        try (var file = new RecordingFile(dump.file())) {
            var matched = new java.util.ArrayDeque<EventSummary>();
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().startsWith(EVENT_PREFIX) || !query.matches(event)) {
                    continue;
                }
                matched.addLast(summary(event));
                if (matched.size() > MAX_EVENTS) {
                    matched.removeFirst();
                }
            }
            var sorted = new ArrayList<>(matched);
            sorted.sort(Comparator.comparing(EventSummary::time));
            return Optional.of(List.copyOf(sorted));
        } finally {
            dump.delete();
        }
    }

    private static final Set<String> STANDARD_FIELDS = Set.of("startTime", "duration", "eventThread", "stackTrace");

    private static EventSummary summary(RecordedEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (ValueDescriptor field : event.getFields()) {
            if (STANDARD_FIELDS.contains(field.getName())) {
                continue;
            }
            Object value = event.getValue(field.getName());
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                fields.put(field.getName(), value);
            }
        }
        var thread = event.getThread();
        return new EventSummary(event.getStartTime(), event.getEventType().getName(),
                event.getEventType().getLabel(), event.getDuration().toMillis(),
                thread == null ? null : thread.getJavaName(), fields);
    }

    private static boolean anyFieldEquals(RecordedEvent event, Set<String> names, String expected) {
        for (String name : names) {
            if (!event.hasField(name)) {
                continue;
            }
            Object value = event.getValue(name);
            if (value != null && expected.equals(value.toString())) {
                return true;
            }
        }
        return false;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A temp file left behind is the OS's to clean; never a failed response.
        }
    }
}

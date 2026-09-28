package io.flowcatalyst.server.diagnostics;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.auth.jwks.TestJwks;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.api.auth.RouterAuth;
import io.flowcatalyst.router.observability.jfr.DispatchEvent;
import io.flowcatalyst.router.observability.jfr.MessageSettledEvent;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/// The operator diagnostics over HTTP (`docs/diagnostics.md`), behind the
/// router API's real guard with real RS256 tokens.
class DiagnosticsRoutesTest {

    private static final String PREFIX = "/router";
    private static final Instant IN_AN_HOUR = Instant.now().plusSeconds(3600);

    private static TestJwks jwks;
    /// Production shape: platform tokens.
    private static TestHttp guarded;
    /// Dev mode with no Basic user: the router API is open, diagnostics are not.
    private static TestHttp devOpen;

    private Optional<ContinuousRecording> recording = Optional.empty();

    @BeforeAll
    static void start() throws Exception {
        jwks = new TestJwks();
        guarded = TestHttp.routes(routes -> {
            var guard = RouterAuth.installGuard(routes,
                    new RouterAuth.Settings(false, "", "", "", PREFIX, jwks.issuer, ""));
            DiagnosticsRoutes.register(routes, PREFIX, guard.authenticates());
        });
        devOpen = TestHttp.routes(routes -> {
            var guard = RouterAuth.installGuard(routes,
                    new RouterAuth.Settings(true, "NONE", "", "", PREFIX, "", ""));
            DiagnosticsRoutes.register(routes, PREFIX, guard.authenticates());
        });
    }

    @AfterAll
    static void stop() {
        guarded.close();
        devOpen.close();
        jwks.close();
    }

    @AfterEach
    void stopRecording() {
        recording.ifPresent(ContinuousRecording::close);
        recording = Optional.empty();
    }

    private static String operate() {
        return "Bearer " + jwks.mintApi("prn_operator", Permission.ROUTER_OPERATE.code(), IN_AN_HOUR);
    }

    private static String viewOnly() {
        return "Bearer " + jwks.mintApi("prn_viewer", Permission.ROUTER_VIEW.code(), IN_AN_HOUR);
    }

    private void startRecording() {
        recording = ContinuousRecording.start(new JfrSettings(true, Duration.ofMinutes(5), 64L * 1024 * 1024, "default"));
        assertThat(recording).as("the test JVM has a flight recorder").isPresent();
    }

    // ── never anonymous ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a thread dump needs router:operate: no token is 401, a view-only token is 403")
    void threadDumpNeedsOperate() {
        assertThat(guarded.get(PREFIX + "/diagnostics/thread-dump").statusCode()).isEqualTo(401);
        assertThat(guarded.get(PREFIX + "/diagnostics/thread-dump", "Authorization", viewOnly()).statusCode())
                .as("mutant: diagnostics read with router:view like any other GET").isEqualTo(403);
        assertThat(guarded.get(PREFIX + "/diagnostics/jfr", "Authorization", viewOnly()).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("dev mode's open router API does not open the diagnostics")
    void devModeWithoutBasicAuthRefuses() {
        var response = devOpen.get(PREFIX + "/diagnostics/thread-dump");

        assertThat(response.statusCode()).as("mutant: serve whatever the guard lets through").isEqualTo(403);
        assertThat(response.body()).contains("DIAGNOSTICS_REQUIRE_AUTH");
    }

    // ── thread dump ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the thread dump includes virtual threads, as the JDK's JSON format")
    void threadDumpIncludesVirtualThreads() throws Exception {
        // Thread.getAllStackTraces() — the obvious implementation — lists platform
        // threads only, and every request and message here runs on a virtual thread.
        var name = "fc-diagnostics-probe-" + UUID.randomUUID();
        var release = new CountDownLatch(1);
        var probe = Thread.ofVirtual().name(name).start(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var response = guarded.get(PREFIX + "/diagnostics/thread-dump", "Authorization", operate());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    ct -> assertThat(ct).startsWith("application/json"));
            var dump = Json.MAPPER.readTree(response.body()).get("threadDump");
            assertThat(dump.get("threadContainers").isArray()).isTrue();
            assertThat(response.body()).as("mutant: platform threads only").contains(name);
        } finally {
            release.countDown();
            probe.join();
        }
    }

    @Test
    @DisplayName("the thread dump's temp file is gone once the response is sent")
    void threadDumpLeavesNothingBehind() throws Exception {
        long before = tempEntries("fc-threads-");

        assertThat(guarded.get(PREFIX + "/diagnostics/thread-dump", "Authorization", operate()).statusCode())
                .isEqualTo(200);

        // Deleted when the adapter closes the stream, just after the last byte.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (tempEntries("fc-threads-") > before && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(tempEntries("fc-threads-")).as("mutant: never delete the dump").isEqualTo(before);
    }

    @Test
    @DisplayName("the fallback dump says virtual threads are missing rather than pretend")
    void fallbackDumpSaysWhatItLacks() throws Exception {
        var body = Json.MAPPER.readTree(Diagnostics.platformThreadsDump("native image"));

        assertThat(body.at("/threadDump/virtualThreadsIncluded").asBoolean(true)).isFalse();
        assertThat(body.at("/threadDump/reason").asString()).isEqualTo("native image");
        assertThat(body.at("/threadDump/threadContainers/0/threads").size()).isPositive();
    }

    // ── flight recording ────────────────────────────────────────────────────

    @Test
    @DisplayName("with no continuous recording the snapshot is a clear 404, not an empty file")
    void noRecordingIs404() {
        var response = guarded.get(PREFIX + "/diagnostics/jfr", "Authorization", operate());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("NO_RECORDING");
    }

    @Test
    @DisplayName("the snapshot is a .jfr download holding the FlowCatalyst events and not the process environment")
    void snapshotHoldsTheEventsAndNoSecrets() throws Exception {
        startRecording();
        var messageId = "msg-" + UUID.randomUUID();
        dispatched(messageId, "g-snapshot", "Success");

        var response = guarded.getBytes(PREFIX + "/diagnostics/jfr?minutes=5", "Authorization", operate());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                cd -> assertThat(cd).startsWith("attachment; filename=\"flowcatalyst-").endsWith(".jfr\""));
        var events = read(response.body());
        assertThat(events).as("the continuous recording carries the router's own events")
                .anySatisfy(e -> {
                    assertThat(e.getEventType().getName()).isEqualTo("io.flowcatalyst.router.Dispatch");
                    assertThat(e.getString("messageId")).isEqualTo(messageId);
                });
        assertThat(events).as("mutant: keep the JDK default of recording every environment variable")
                .noneMatch(e -> e.getEventType().getName().equals("jdk.InitialEnvironmentVariable")
                        || e.getEventType().getName().equals("jdk.InitialSystemProperty"));
        assertThat(events).as("the JDK's own default profile is recording too")
                .anyMatch(e -> e.getEventType().getName().startsWith("jdk."));
    }

    @Test
    @DisplayName("the events query answers what happened to one message, with the reasons, and nothing else")
    void eventsForOneMessage() throws Exception {
        startRecording();
        var messageId = "msg-" + UUID.randomUUID();
        var other = "msg-" + UUID.randomUUID();
        dispatched(messageId, "g-events", "ErrorProcess");
        settled(messageId, "nack", "target-unavailable");
        dispatched(other, "g-events", "Success");

        var response = guarded.get(PREFIX + "/diagnostics/jfr/events?messageId=" + messageId + "&minutes=5",
                "Authorization", operate());

        assertThat(response.statusCode()).isEqualTo(200);
        var events = Json.MAPPER.readTree(response.body()).get("events");
        assertThat(events.size()).as("mutant: no filter").isEqualTo(2);
        assertThat(events.get(0).at("/type").asString()).isEqualTo("io.flowcatalyst.router.Dispatch");
        assertThat(events.get(0).at("/fields/outcome").asString()).isEqualTo("ErrorProcess");
        assertThat(events.get(1).at("/type").asString()).isEqualTo("io.flowcatalyst.router.MessageSettled");
        assertThat(events.get(1).at("/fields/reason").asString()).isEqualTo("target-unavailable");

        var byGroup = guarded.get(PREFIX + "/diagnostics/jfr/events?group=g-events&minutes=5",
                "Authorization", operate());
        assertThat(Json.MAPPER.readTree(byGroup.body()).get("events").size())
                .as("both messages' dispatches carry the group; the settlement does not").isEqualTo(2);
    }

    @Test
    @DisplayName("the events query needs a message id or a group")
    void eventsNeedAFilter() {
        assertThat(guarded.get(PREFIX + "/diagnostics/jfr/events", "Authorization", operate()).statusCode())
                .isEqualTo(400);
        assertThat(guarded.get(PREFIX + "/diagnostics/jfr?minutes=0", "Authorization", operate()).statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("the capabilities answer names the runtime and the recording's bounds")
    void capabilities() throws Exception {
        startRecording();

        var body = Json.MAPPER.readTree(guarded.get(PREFIX + "/diagnostics", "Authorization", operate()).body());

        assertThat(body.at("/runtime").asString()).isEqualTo("hotspot");
        assertThat(body.at("/jfr/recording").asBoolean()).isTrue();
        assertThat(body.at("/jfr/maxAgeSeconds").asLong()).isEqualTo(300);
        assertThat(body.at("/threadDump/virtualThreads").asBoolean()).isTrue();
    }

    private static void dispatched(String messageId, String group, String outcome) {
        var event = new DispatchEvent();
        event.pool = "POOL-A";
        event.messageId = messageId;
        event.queue = "q://1";
        event.group = group;
        event.outcome = outcome;
        event.commit();
    }

    private static void settled(String messageId, String action, String reason) {
        var event = new MessageSettledEvent();
        event.messageId = messageId;
        event.queue = "q://1";
        event.action = action;
        event.reason = reason;
        event.commit();
    }

    private static List<RecordedEvent> read(byte[] jfr) throws Exception {
        var file = Files.createTempFile("diagnostics-test", ".jfr");
        try {
            Files.write(file, jfr);
            return RecordingFile.readAllEvents(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static long tempEntries(String prefix) throws Exception {
        try (var entries = Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
            return entries.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }
}

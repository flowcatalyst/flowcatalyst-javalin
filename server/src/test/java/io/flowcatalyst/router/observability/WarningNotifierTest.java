package io.flowcatalyst.router.observability;

import tools.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.observability.Warnings.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Teams delivery, asserted against a real HTTP server and the actual JSON a
/// card carries — the Java reading of the Rust `fc-router`'s
/// `TeamsWebhookNotificationService` + `BatchingNotificationService`
/// (`crates/fc-router/src/notification.rs`, `docs/spec/router-env.md` §4).
///
/// The point of this class is that a notice reaches a person, in the shape
/// Teams actually renders. Asserting that `raise()` was called would prove
/// the opposite of what matters, so every test here reads what the webhook
/// actually received.
@SuppressWarnings("deprecation")
class WarningNotifierTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private HttpServer server;
    private URI hook;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private WarningNotifier notifier;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", this::handle);
        server.start();
        hook = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        notifier = new WarningNotifier(hook, HttpClient.newHttpClient(), Duration.ofHours(1), Severity.WARNING, FIXED);
    }

    @AfterEach
    void stopServer() {
        notifier.close();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(status.get(), -1);
        exchange.close();
    }

    // ── batching ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the summary header names the batch window in Rust's space-separated timestamp form")
    void summaryHeaderNamesTheWindow() {
        notifier.raise(Severity.WARNING, "ROUTING", "one");

        notifier.flush();

        assertThat(summaryMessage(received.getFirst()))
                .startsWith("FlowCatalyst Warning Summary (2026-01-01 00:00:00 to 2026-01-01 00:00:00)");
    }

    @Test
    @DisplayName("100 WARNINGs within one interval produce exactly one POST whose summary counts 100")
    void largeBatchProducesOneSummaryCard() {
        // Kills the restored flush-at-20 mutant: with that behaviour back,
        // 100 warnings would produce five POSTs (20 each), not one.
        IntStream.range(0, 100).forEach(i -> notifier.raise(Severity.WARNING, "ROUTING", "warning " + i));
        assertThat(received).as("still batching; nothing sent until the tick").isEmpty();

        notifier.flush();

        assertThat(received).hasSize(1);
        var summary = summaryMessage(received.getFirst());
        assertThat(summary).contains("Total Warnings: 100");
    }

    @Test
    @DisplayName("more than one warning in the same category is counted, not listed")
    void repeatedCategoryIsCountedWithAnExample() {
        notifier.raise(Severity.WARNING, "ROUTING", "first one");
        notifier.raise(Severity.WARNING, "ROUTING", "second one");
        notifier.raise(Severity.WARNING, "ROUTING", "third one");
        notifier.raise(Severity.ERROR, "CONFIGURATION", "the only one");

        notifier.flush();

        var summary = summaryMessage(received.getFirst());
        assertThat(summary).contains("  - ROUTING: 3 occurrences").contains("    Example: first one");
        assertThat(summary).contains("  - CONFIGURATION: the only one");
        assertThat(summary).doesNotContain("second one").doesNotContain("third one");
        assertThat(summary).contains("Total Warnings: 4");
    }

    @Test
    @DisplayName("the summary groups by severity in Critical, Error, Warn, Info order and names the section")
    void summaryGroupsBySeverity() {
        received.clear();
        var infoFloor = new WarningNotifier(hook, HttpClient.newHttpClient(), Duration.ofHours(1),
                Warnings.parseMinSeverity("INFO"), FIXED);
        try {
            infoFloor.raise(Severity.INFO, "RATE_LIMIT", "an info");
            infoFloor.raise(Severity.WARNING, "ROUTING", "a warning");
            infoFloor.raise(Severity.ERROR, "CONFIGURATION", "an error");

            infoFloor.flush();

            assertThat(received).hasSize(1);
            var summary = summaryMessage(received.getFirst());
            assertThat(summary.indexOf("Error Issues")).as("Error before Warn").isLessThan(summary.indexOf("Warn Issues"));
            assertThat(summary.indexOf("Warn Issues")).as("Warn before Info").isLessThan(summary.indexOf("Info Issues"));
        } finally {
            infoFloor.close();
        }
    }

    // ── CRITICAL bypasses batching ──────────────────────────────────────

    @Test
    @DisplayName("a CRITICAL is delivered at once as its own card, and never enters the batch")
    void criticalIsDeliveredImmediatelyAndExcludedFromTheBatch() {
        // Kills the "CRITICAL batched instead of immediate" mutant.
        notifier.raise(Severity.CRITICAL, "CONFIGURATION", "HTTP 501: not implemented");

        assertThat(received).hasSize(1);
        var card = cardContent(received.getFirst());
        var body = card.get("body");
        assertThat(body.get(0).get("items").get(0).get("text").asText()).contains("CRITICAL ERROR");
        var facts = body.get(1).get("facts");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).get("title").asText()).isEqualTo("Source:");
        assertThat(facts.get(0).get("value").asText()).isEqualTo("CONFIGURATION");
        assertThat(body.get(2).get("text").asText()).contains("501");

        notifier.raise(Severity.WARNING, "ROUTING", "an ordinary one");
        notifier.flush();

        assertThat(received).hasSize(2);
        var summary = summaryMessage(received.get(1));
        assertThat(summary).as("the CRITICAL never entered the batch").doesNotContain("501").doesNotContain("Critical Issues");
        assertThat(summary).contains("Total Warnings: 1");
    }

    // ── severity floor ───────────────────────────────────────────────────

    @Test
    @DisplayName("warnings below the threshold are never sent")
    void belowThresholdIsDropped() {
        // Kills the "min-severity filter dropped" mutant.
        notifier.raise(Severity.INFO, "ROUTING", "noise");
        notifier.raise(Severity.INFO, "ROUTING", "more noise");
        notifier.flush();

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("a notifier built with the parsed INFO floor actually pushes INFO, not just stores it above the default WARNING floor")
    void parsedInfoFloorActuallyPushesInfo() {
        var infoNotifier = new WarningNotifier(hook, HttpClient.newHttpClient(), Duration.ZERO,
                Warnings.parseMinSeverity("INFO"), FIXED);
        try {
            infoNotifier.raise(Severity.INFO, "RATE_LIMIT", "back to unlimited");

            assertThat(received).as("INFO configured as the floor must be delivered, not dropped").hasSize(1);
        } finally {
            infoNotifier.close();
        }
    }

    @Test
    @DisplayName("X-04: FC_NOTIFY_MIN_SEVERITY accepts WARN as well as WARNING, case-insensitively")
    void parseMinSeverityAcceptsWarnAndWarning() {
        assertThat(Warnings.parseMinSeverity("WARN")).isEqualTo(Severity.WARNING);
        assertThat(Warnings.parseMinSeverity("warn")).isEqualTo(Severity.WARNING);
        assertThat(Warnings.parseMinSeverity("WARNING")).isEqualTo(Severity.WARNING);
        assertThat(Warnings.parseMinSeverity("INFO")).isEqualTo(Severity.INFO);
        assertThat(Warnings.parseMinSeverity(null)).isEqualTo(Severity.WARNING);
        assertThat(Warnings.parseMinSeverity("garbage")).isEqualTo(Severity.WARNING);
    }

    // ── interval 0: no batching ──────────────────────────────────────────

    @Test
    @DisplayName("interval 0 sends each notice at once as its own card, never a summary")
    void zeroIntervalSendsEachNoticeImmediately() {
        var immediate = new WarningNotifier(hook, HttpClient.newHttpClient(), Duration.ZERO, Severity.WARNING, FIXED);
        try {
            immediate.raise(Severity.WARNING, "ROUTING", "one");
            immediate.raise(Severity.WARNING, "ROUTING", "two");
            immediate.raise(Severity.ERROR, "CONFIGURATION", "three");

            assertThat(received).hasSize(3);
            var first = cardContent(received.get(0));
            assertThat(first.get("body").get(1).get("facts").get(0).get("value").asText()).isEqualTo("ROUTING");
            assertThat(first.get("body").get(3).get("text").asText()).isEqualTo("one");
        } finally {
            immediate.close();
        }
    }

    // ── create(): webhook + NOTIFICATION_TEAMS_ENABLED ──────────────────

    @Test
    @DisplayName("an unset webhook is a silent no-op, a malformed one is not fatal")
    void unconfiguredIsNoOp() {
        assertThat(WarningNotifier.create(null, "", Severity.WARNING, Duration.ofSeconds(300), FIXED))
                .isSameAs(Warnings.NO_OP);
        assertThat(WarningNotifier.create("  ", "", Severity.WARNING, Duration.ofSeconds(300), FIXED))
                .isSameAs(Warnings.NO_OP);
        assertThat(WarningNotifier.create("not a uri", "", Severity.WARNING, Duration.ofSeconds(300), FIXED))
                .isSameAs(Warnings.NO_OP);
    }

    @Test
    @DisplayName("explicit NOTIFICATION_TEAMS_ENABLED=false disables even with a webhook configured (deliberate Rust deviation)")
    void explicitlyDisabledIsNoOpEvenWithAUrl() {
        // Kills the "explicit NOTIFICATION_TEAMS_ENABLED=false ignored" mutant.
        assertThat(WarningNotifier.create(hook.toString(), "false", Severity.WARNING, Duration.ofSeconds(300), FIXED))
                .isSameAs(Warnings.NO_OP);
        for (var word : new String[] {"0", "no", "off", "FALSE"}) {
            assertThat(WarningNotifier.create(hook.toString(), word, Severity.WARNING, Duration.ofSeconds(300), FIXED))
                    .as(word).isSameAs(Warnings.NO_OP);
        }
    }

    @Test
    @DisplayName("an absent or true NOTIFICATION_TEAMS_ENABLED still notifies when a webhook is configured")
    void unsetOrTrueStillNotifies() {
        assertThat(WarningNotifier.create(hook.toString(), "", Severity.WARNING, Duration.ofSeconds(300), FIXED))
                .isInstanceOf(WarningNotifier.class);
        assertThat(WarningNotifier.create(hook.toString(), "true", Severity.WARNING, Duration.ofSeconds(300), FIXED))
                .isInstanceOf(WarningNotifier.class);
    }

    // ── card shape ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the card envelope matches Teams' Adaptive Card contract exactly")
    void cardEnvelopeShape() {
        notifier.raise(Severity.CRITICAL, "CONFIGURATION", "envelope check");

        var root = body(received.getFirst());
        assertThat(root.get("attachments").get(0).get("contentType").asText())
                .isEqualTo("application/vnd.microsoft.card.adaptive");
        assertThat(root.get("attachments").get(0).get("content").get("type").asText()).isEqualTo("AdaptiveCard");
        assertThat(root.get("attachments").get(0).get("content").get("version").asText()).isEqualTo("1.4");
    }

    @Test
    @DisplayName("a warning card carries Category/Source/Time facts, severity colour, and the Warn display name")
    void warningCardFacts() {
        var immediate = new WarningNotifier(hook, HttpClient.newHttpClient(), Duration.ZERO, Severity.WARNING, FIXED);
        try {
            immediate.raise(Severity.WARNING, "ROUTING", "a routing problem");
        } finally {
            immediate.close();
        }

        var card = cardContent(received.getFirst());
        var facts = card.get("body").get(1).get("facts");
        assertThat(facts.get(0).get("title").asText()).isEqualTo("Category:");
        assertThat(facts.get(0).get("value").asText()).isEqualTo("ROUTING");
        assertThat(facts.get(1).get("title").asText()).isEqualTo("Source:");
        assertThat(facts.get(1).get("value").asText()).isEqualTo("ROUTING");
        assertThat(facts.get(2).get("title").asText()).isEqualTo("Time:");
        assertThat(facts.get(2).get("value").asText()).isEqualTo("2026-01-01T00:00:00");

        var columns = card.get("body").get(0).get("items").get(0).get("columns");
        var subtitle = columns.get(1).get("items").get(1);
        assertThat(subtitle.get("text").asText()).isEqualTo("Warn - ROUTING");
        assertThat(subtitle.get("color").asText()).isEqualTo("Warning");
    }

    @Test
    @DisplayName("severity colours match Rust: Attention for Critical/Error, Warning for Warn, Accent for Info")
    void severityColours() {
        assertColour(Severity.ERROR, "Attention");
        assertColour(Severity.WARNING, "Warning");
        assertColour(Severity.INFO, "Accent");
    }

    private void assertColour(Severity severity, String expected) {
        received.clear();
        var infoFloor = new WarningNotifier(hook, HttpClient.newHttpClient(), Duration.ZERO,
                Warnings.parseMinSeverity("INFO"), FIXED);
        try {
            infoFloor.raise(severity, "X", "y");
        } finally {
            infoFloor.close();
        }
        var card = cardContent(received.getFirst());
        var subtitle = card.get("body").get(0).get("items").get(0).get("columns").get(1).get("items").get(1);
        assertThat(subtitle.get("color").asText()).as(severity.toString()).isEqualTo(expected);
    }

    // ── shutdown / delivery failure containment ─────────────────────────

    @Test
    @DisplayName("closing delivers what is still pending")
    void closeFlushesPending() {
        notifier.raise(Severity.ERROR, "CONFIGURATION", "just before we go");

        notifier.close();

        assertThat(received).hasSize(1);
        assertThat(summaryMessage(received.getFirst())).contains("just before we go");
    }

    @Test
    @DisplayName("a webhook that is down never propagates into the caller")
    void deliveryFailureIsContained() {
        server.stop(0);

        assertThatCode(() -> notifier.raise(Severity.CRITICAL, "CONFIGURATION", "nobody home"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a non-2xx from the webhook is contained too")
    void nonSuccessIsContained() {
        status.set(500);

        assertThatCode(() -> notifier.raise(Severity.CRITICAL, "CONFIGURATION", "rejected"))
                .doesNotThrowAnyException();
        assertThat(received).as("it was attempted").hasSize(1);
    }

    @Test
    @DisplayName("tee reaches every sink, and one that throws does not stop the others")
    void teeIsolatesSinks() {
        var store = new WarningStore(FIXED);
        Warnings exploding = (severity, category, message) -> {
            throw new IllegalStateException("sink is broken");
        };

        var sink = Warnings.tee(exploding, store, notifier);
        assertThatCode(() -> sink.raise(Severity.CRITICAL, "CONFIGURATION", "reaches both"))
                .doesNotThrowAnyException();

        assertThat(store.count()).as("the store still got it").isEqualTo(1);
        assertThat(received).as("and so did the webhook").hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static JsonNode body(String raw) {
        try {
            return Json.MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("webhook body was not JSON: " + raw, e);
        }
    }

    private static JsonNode cardContent(String raw) {
        return body(raw).get("attachments").get(0).get("content");
    }

    /// The message `TextBlock` of a warning/summary card — index 3 in
    /// [WarningNotifier]'s `buildWarningCard` body (header container,
    /// FactSet, "Message" label, then the message itself).
    private static String summaryMessage(String raw) {
        return cardContent(raw).get("body").get(3).get("text").asText();
    }
}

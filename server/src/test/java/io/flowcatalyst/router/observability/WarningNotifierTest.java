package io.flowcatalyst.router.observability;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/// Warning delivery, asserted against a real HTTP server.
///
/// The point of this class is that a notice reaches a person. Asserting that
/// `raise()` was called would prove the opposite of what matters, so every
/// test here reads what the webhook actually received.
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
        notifier = new WarningNotifier(hook, HttpClient.newHttpClient(), 3,
                Duration.ofHours(1), Severity.WARNING, FIXED);
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

    @Test
    @DisplayName("a CRITICAL is delivered immediately, not batched")
    void criticalIsDeliveredAtOnce() throws Exception {
        // The one severity that must not wait for a tick. A 501 means every
        // message to that target is being deleted; thirty seconds of batching
        // is thirty seconds of silent loss.
        notifier.raise(Severity.CRITICAL, "CONFIGURATION", "HTTP 501: not implemented");

        assertThat(received).hasSize(1);
        var warnings = body(received.getFirst()).get("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(warnings.get(0).get("category").asText()).isEqualTo("CONFIGURATION");
        assertThat(warnings.get(0).get("message").asText()).contains("501");
        assertThat(warnings.get(0).has("raisedAt")).isTrue();
    }

    @Test
    @DisplayName("lesser warnings batch until the batch is full")
    void lesserWarningsBatch() {
        // Batching is what stops a flapping target turning one incident into
        // a thousand messages, which is how a channel gets muted.
        notifier.raise(Severity.ERROR, "CONFIGURATION", "one");
        notifier.raise(Severity.ERROR, "CONFIGURATION", "two");
        assertThat(received).as("still batching").isEmpty();

        notifier.raise(Severity.ERROR, "CONFIGURATION", "three");

        assertThat(received).hasSize(1);
        assertThat(body(received.getFirst()).get("warnings")).hasSize(3);
    }

    @Test
    @DisplayName("warnings below the threshold are never sent")
    void belowThresholdIsDropped() {
        notifier.raise(Severity.INFO, "ROUTING", "noise");
        notifier.raise(Severity.INFO, "ROUTING", "more noise");
        notifier.flush();

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("closing delivers what is still pending")
    void closeFlushesPending() {
        // A shutting-down instance's last notices are the ones most likely to
        // explain why it is shutting down.
        notifier.raise(Severity.ERROR, "CONFIGURATION", "just before we go");

        notifier.close();

        assertThat(received).hasSize(1);
        assertThat(body(received.getFirst()).get("warnings").get(0).get("message").asText())
                .isEqualTo("just before we go");
    }

    @Test
    @DisplayName("a webhook that is down never propagates into the caller")
    void deliveryFailureIsContained() {
        // The caller is a message-delivery path that has already hit a
        // problem. A notice about it must not be able to make things worse.
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
    @DisplayName("an unset webhook is a silent no-op, a malformed one is not fatal")
    void unconfiguredIsNoOp() {
        // An unconfigured webhook is the normal developer case and must be
        // silent; a malformed one is a typo someone wants to hear about, but
        // still must not stop the router starting.
        assertThat(WarningNotifier.create(null, Severity.WARNING, FIXED)).isSameAs(Warnings.NO_OP);
        assertThat(WarningNotifier.create("  ", Severity.WARNING, FIXED)).isSameAs(Warnings.NO_OP);
        assertThat(WarningNotifier.create("not a uri", Severity.WARNING, FIXED)).isSameAs(Warnings.NO_OP);
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

    private static JsonNode body(String raw) {
        try {
            return Json.MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("webhook body was not JSON: " + raw, e);
        }
    }
}

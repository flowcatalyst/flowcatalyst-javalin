package io.flowcatalyst.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.outbox.jfr.OutboxItemSettledEvent;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.testjfr.Recorded;
import jdk.jfr.consumer.RecordedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.flowcatalyst.outbox.OutboxFixture.DS;
import static io.flowcatalyst.outbox.OutboxFixture.id;
import static io.flowcatalyst.outbox.OutboxFixture.seedRow;
import static org.assertj.core.api.Assertions.assertThat;

/// The `OutboxItemSettled` flight-recorder event (`docs/spec/jfr-events.md`
/// §2), read back out of a real recording — same fixture as
/// `OutboxProcessorTest` (embedded Postgres + a stub HTTP server), asserting
/// the recorded fields rather than the row's live status.
class OutboxEventsTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> statusFor = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode root = Json.MAPPER.readTree(body);
        ArrayNode results = Json.MAPPER.createArrayNode();
        for (JsonNode itemNode : root.get("items")) {
            String rowId = itemNode.get("id").stringValue();
            ObjectNode result = Json.MAPPER.createObjectNode();
            result.put("id", rowId);
            result.put("status", statusFor.getOrDefault(rowId, "SUCCESS"));
            results.add(result);
        }
        ObjectNode responseNode = Json.MAPPER.createObjectNode();
        responseNode.set("results", results);
        byte[] bytes = Json.MAPPER.writeValueAsString(responseNode).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String payload(String rowId) {
        return "{\"id\":\"" + rowId + "\"}";
    }

    private OutboxProcessor.Config config(int maxRetries) {
        return new OutboxProcessor.Config(100, 1000, Duration.ofSeconds(1), 10, true, maxRetries,
                Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(5));
    }

    private OutboxProcessor processor(BooleanSupplier leader, OutboxProcessor.Config cfg) {
        var repo = new PostgresOutboxRepository(DS);
        var dispatcher = new HttpDispatcher(HttpDispatcher.defaultClient(), baseUrl, Duration.ofSeconds(5), null, "t");
        return new OutboxProcessor(repo, dispatcher, cfg, leader);
    }

    // ── success -> outcome=SUCCESS, status=DELIVERED, persisted=true ────────

    @Test
    void successRecordsOutcomeSuccessAndPersistedTrue() throws Exception {
        String rowId = id("jfr-success");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "SUCCESS");
        var processor = processor(() -> true, config(3));

        var events = Recorded.from(OutboxItemSettledEvent.class, () -> {
            processor.pollOnce();
            awaitNoRow(rowId);
        });

        var settled = only(events, rowId);
        assertThat(settled.getString("type")).isEqualTo("EVENT");
        assertThat(settled.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(settled.getString("status")).isEqualTo("DELIVERED");
        assertThat(settled.getBoolean("requeued")).isFalse();
        assertThat(settled.getBoolean("grouped")).isFalse();
        assertThat(settled.getBoolean("persisted")).as("markSuccess returned normally").isTrue();
    }

    // ── a mark that throws -> persisted=false, and the event is still recorded ──

    /// The `persisted` field's only reason to exist (spec §2): the platform
    /// accepted the item but the repository could not record it, so the row
    /// stays claimed and will be re-delivered after recovery. A delegating
    /// repository whose `markSuccess` throws is the failure; the row must be
    /// untouched afterwards.
    @Test
    void aFailingMarkRecordsPersistedFalse() throws Exception {
        String rowId = id("jfr-mark-throws");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "SUCCESS");
        var real = new PostgresOutboxRepository(DS);
        OutboxRepository throwingOnMark = new OutboxRepository() {
            @Override public void initSchema() { real.initSchema(); }
            @Override public List<OutboxItem> claimPending(int n) { return real.claimPending(n); }
            @Override public void markSuccess(List<String> ids) { throw new PostgresOutboxRepository.OutboxSqlException("markSuccess: simulated outage", new RuntimeException()); }
            @Override public void markFailed(List<String> ids, OutboxStatus status, String message, boolean requeue) { real.markFailed(ids, status, message, requeue); }
            @Override public void release(List<String> ids) { real.release(ids); }
            @Override public void requeue(List<String> ids) { real.requeue(ids); }
            @Override public int recoverStuck(Duration olderThan) { return real.recoverStuck(olderThan); }
            @Override public boolean healthy() { return real.healthy(); }
        };
        var dispatcher = new HttpDispatcher(HttpDispatcher.defaultClient(), baseUrl, Duration.ofSeconds(5), null, "t");
        var processor = new OutboxProcessor(throwingOnMark, dispatcher, config(3), () -> true);

        var events = Recorded.from(OutboxItemSettledEvent.class, () -> {
            processor.pollOnce();
            await(() -> processor.inFlight() == 0); // the dispatch is asynchronous; the event follows the mark
        });

        var settled = only(events, rowId);
        assertThat(settled.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(settled.getBoolean("persisted")).as("markSuccess threw").isFalse();
    }

    // ── retryable failure -> outcome=FAILURE, status=the failure status, requeued=true ──

    @Test
    void retryableFailureRecordsTheStatusAndRequeuedTrue() throws Exception {
        String rowId = id("jfr-retry");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "GATEWAY_ERROR");
        var processor = processor(() -> true, config(3));

        var events = Recorded.from(OutboxItemSettledEvent.class, () -> {
            processor.pollOnce();
            awaitRetryCount(rowId, 1);
        });

        var settled = only(events, rowId);
        assertThat(settled.getString("outcome")).isEqualTo("FAILURE");
        assertThat(settled.getString("status")).isEqualTo(OutboxStatus.GATEWAY_ERROR.name());
        assertThat(settled.getBoolean("requeued")).as("retryable, budget remains").isTrue();
        assertThat(settled.getBoolean("persisted")).isTrue();
    }

    // ── terminal failure -> requeued=false ───────────────────────────────────

    @Test
    void terminalFailureRecordsRequeuedFalse() throws Exception {
        String rowId = id("jfr-terminal");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "BAD_REQUEST");
        var processor = processor(() -> true, config(3));

        var events = Recorded.from(OutboxItemSettledEvent.class, () -> {
            processor.pollOnce();
            awaitRetryCount(rowId, 1);
        });

        var settled = only(events, rowId);
        assertThat(settled.getString("outcome")).isEqualTo("FAILURE");
        assertThat(settled.getString("status")).isEqualTo(OutboxStatus.BAD_REQUEST.name());
        assertThat(settled.getBoolean("requeued")).as("BAD_REQUEST is terminal, never requeued").isFalse();
    }

    // ── a grouped item's group and grouped=true ──────────────────────────────

    @Test
    void groupedItemRecordsGroupedTrueAndTheMessageGroup() throws Exception {
        String group = id("jfr-group");
        String rowId = id("jfr-grouped-item");
        seedRow(rowId, OutboxItemType.EVENT, group, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "SUCCESS");
        var processor = processor(() -> true, config(3));

        var events = Recorded.from(OutboxItemSettledEvent.class, () -> {
            processor.pollOnce();
            awaitNoRow(rowId);
        });

        var settled = only(events, rowId);
        assertThat(settled.getBoolean("grouped")).as("dispatched on the per-group path").isTrue();
        assertThat(settled.getString("group")).isEqualTo(group);
    }

    private static void awaitNoRow(String rowId) {
        await(() -> !OutboxFixture.exists(rowId));
    }

    private static void awaitRetryCount(String rowId, int retryCount) {
        await(() -> {
            var row = OutboxFixture.row(rowId);
            return row != null && row.retryCount() == retryCount;
        });
    }

    private static void await(Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition never became true within the wait budget");
    }

    private static RecordedEvent only(List<RecordedEvent> events, String itemId) {
        var matches = events.stream().filter(e -> e.getString("itemId").equals(itemId)).toList();
        assertThat(matches).as("exactly one event for item " + itemId).hasSize(1);
        return matches.getFirst();
    }
}

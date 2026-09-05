package io.flowcatalyst.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.json.Json;
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
import static io.flowcatalyst.outbox.OutboxFixture.row;
import static io.flowcatalyst.outbox.OutboxFixture.seedRow;
import static org.assertj.core.api.Assertions.assertThat;

/// [OutboxProcessor] against a real repository (embedded Postgres) and a
/// stub HTTP server: the poll tick (grouped and ungrouped routing,
/// back-pressure, leader gating), the outcome handling (requeue vs
/// terminal, group block), the admin verbs, and the recovery tick.
///
/// Every item's payload IS its own row id (`{"id":"<rowId>"}`) so the stub
/// server can answer per-row without needing to know batching/grouping —
/// [#statusFor] maps a row id to the wire status the stub returns for it.
/// Grouped and ungrouped dispatch both happen on background (virtual)
/// threads even when [OutboxProcessor#pollOnce] is called synchronously, so
/// every assertion after a tick goes through [#await].
class OutboxProcessorTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> statusFor = new ConcurrentHashMap<>();
    private final Map<String, String> errorFor = new ConcurrentHashMap<>();

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
            String err = errorFor.get(rowId);
            if (err != null) {
                result.put("error", err);
            }
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

    private OutboxProcessor.Config config(int maxInFlight, int maxRetries, boolean blockOnError) {
        return new OutboxProcessor.Config(100, maxInFlight, Duration.ofSeconds(1), 10, blockOnError, maxRetries,
                Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(5));
    }

    private OutboxProcessor processor(BooleanSupplier leader, OutboxProcessor.Config cfg) {
        var repo = new PostgresOutboxRepository(DS);
        var dispatcher = new HttpDispatcher(HttpDispatcher.defaultClient(), baseUrl, Duration.ofSeconds(5), null, "t");
        return new OutboxProcessor(repo, dispatcher, cfg, leader);
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

    // ── leader gate / back-pressure (spec §9) ───────────────────────────────

    @Test
    void aNonLeaderTickClaimsNothing() {
        String rowId = id("notleader");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        var processor = processor(() -> false, config(1000, 3, true));

        invokePollOnce(processor);

        assertThat(row(rowId).status()).as("a non-leader tick must not claim").isEqualTo(0);
    }

    @Test
    void atMaxInFlightATickSkipsTheClaimEntirely() {
        String rowId = id("backpressure");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        var processor = processor(() -> true, config(0, 3, true)); // maxInFlight=0: inFlight(0) >= 0 always

        invokePollOnce(processor);

        assertThat(row(rowId).status()).as("back-pressure must skip the claim, not just the dispatch").isEqualTo(0);
    }

    // ── ungrouped dispatch (spec §4) ─────────────────────────────────────────

    @Test
    void ungroupedSuccessDeletesTheRow() {
        String rowId = id("ungrouped-success");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "SUCCESS");
        var processor = processor(() -> true, config(1000, 3, true));

        invokePollOnce(processor);

        await(() -> !OutboxFixture.exists(rowId));
        assertThat(processor.totals().succeeded()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void ungroupedRetryableFailureRequeuesWithBumpedRetryCount() {
        String rowId = id("ungrouped-retry");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "GATEWAY_ERROR");
        errorFor.put(rowId, "upstream down");
        var processor = processor(() -> true, config(1000, 3, true));

        invokePollOnce(processor);

        await(() -> row(rowId) != null && row(rowId).retryCount() == 1);
        var r = row(rowId);
        assertThat(r.status()).as("requeued back to PENDING").isEqualTo(0);
        assertThat(r.errorMessage()).isEqualTo("upstream down");
    }

    @Test
    void ungroupedTerminalFailureWritesTheStatusAndNeverRequeues() {
        String rowId = id("ungrouped-terminal");
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "BAD_REQUEST");
        var processor = processor(() -> true, config(1000, 3, true));

        invokePollOnce(processor);

        await(() -> row(rowId) != null && row(rowId).status() == OutboxStatus.BAD_REQUEST.code());
        assertThat(row(rowId).retryCount()).isEqualTo(1);
    }

    @Test
    void retryLadderExhaustsToTerminalRegardlessOfRetryableStatus() {
        String rowId = id("ladder-exhaust");
        // retryCount already 2: attemptCount+1 (3) is NOT < maxRetries (3) -> terminal write, no more requeue.
        seedRow(rowId, OutboxItemType.EVENT, null, payload(rowId), 0, 2, Instant.now());
        statusFor.put(rowId, "INTERNAL_ERROR");
        var processor = processor(() -> true, config(1000, 3, true));

        invokePollOnce(processor);

        await(() -> row(rowId) != null && row(rowId).status() == OutboxStatus.INTERNAL_ERROR.code());
        assertThat(row(rowId).retryCount()).isEqualTo(3);
    }

    // ── grouped dispatch: block-on-error (spec §5, the load-bearing test) ───

    @Test
    void aPermanentFailureBlocksTheGroupAndAbortsQueuedSiblingsUntouched() {
        String group = id("block-group");
        String head = id("block-head");
        String mid = id("block-mid");
        String tail = id("block-tail");
        Instant t = Instant.now().minusSeconds(5);
        seedRow(head, OutboxItemType.EVENT, group, payload(head), 0, 0, t);
        seedRow(mid, OutboxItemType.EVENT, group, payload(mid), 0, 0, t.plusMillis(1));
        seedRow(tail, OutboxItemType.EVENT, group, payload(tail), 0, 0, t.plusMillis(2));
        statusFor.put(head, "BAD_REQUEST"); // terminal, not requeued
        var processor = processor(() -> true, config(1000, 3, true));

        invokePollOnce(processor);

        await(() -> !processor.blockedGroups().isEmpty());
        assertThat(processor.blockedGroups()).extracting(GroupStateManager.GroupInfo::blockedItemId)
                .containsExactly(head);

        var headRow = row(head);
        assertThat(headRow.status()).as("the head is dispatched and fails terminally").isEqualTo(OutboxStatus.BAD_REQUEST.code());
        assertThat(headRow.retryCount()).isEqualTo(1);

        var midRow = row(mid);
        var tailRow = row(tail);
        assertThat(midRow.status()).as("aborted back to PENDING, never dispatched").isEqualTo(0);
        assertThat(midRow.retryCount()).as("release must not bump retry_count").isEqualTo(0);
        assertThat(tailRow.status()).isEqualTo(0);
        assertThat(tailRow.retryCount()).isEqualTo(0);
    }

    @Test
    void aRetryableGroupedFailureDoesNotBlockAndTheGroupContinues() {
        String group = id("retry-group");
        String head = id("retry-head");
        String tail = id("retry-tail");
        Instant t = Instant.now().minusSeconds(5);
        seedRow(head, OutboxItemType.EVENT, group, payload(head), 0, 0, t);
        seedRow(tail, OutboxItemType.EVENT, group, payload(tail), 0, 0, t.plusMillis(1));
        statusFor.put(head, "GATEWAY_ERROR"); // retryable, budget remains -> requeued, group continues
        statusFor.put(tail, "SUCCESS");
        var processor = processor(() -> true, config(1000, 3, true));

        invokePollOnce(processor);

        await(() -> !OutboxFixture.exists(tail));
        assertThat(processor.blockedGroups()).as("a requeued (retryable) failure must not block the group").isEmpty();
        var headRow = row(head);
        assertThat(headRow.status()).as("requeued to PENDING, not blocked").isEqualTo(0);
        assertThat(headRow.retryCount()).isEqualTo(1);
    }

    @Test
    void aPausedGroupsItemsAreReleasedNeverDispatched() {
        String group = id("paused-group");
        String rowId = id("paused-item");
        seedRow(rowId, OutboxItemType.EVENT, group, payload(rowId), 0, 0, Instant.now());
        statusFor.put(rowId, "SUCCESS"); // would succeed if ever dispatched
        var processor = processor(() -> true, config(1000, 3, true));
        assertThat(processor.pauseGroup(group)).isTrue();

        invokePollOnce(processor);

        var r = row(rowId);
        assertThat(r).as("released synchronously inside pollOnce — no wait needed").isNotNull();
        assertThat(r.status()).isEqualTo(0);
        assertThat(r.retryCount()).isEqualTo(0);
    }

    // ── admin verbs (spec §5, §7) ────────────────────────────────────────────

    @Test
    void unblockRequeuesThePoisonItemWithRetryCountResetAndReactivatesTheGroup() {
        String group = id("unblock-group");
        String head = id("unblock-head");
        seedRow(head, OutboxItemType.EVENT, group, payload(head), 0, 1, Instant.now());
        statusFor.put(head, "BAD_REQUEST");
        var processor = processor(() -> true, config(1000, 3, true));
        invokePollOnce(processor);
        await(() -> !processor.blockedGroups().isEmpty());

        assertThat(processor.unblockGroup(group)).isTrue();

        assertThat(processor.blockedGroups()).isEmpty();
        var r = row(head);
        assertThat(r.status()).as("unblock requeues the poison item").isEqualTo(0);
        assertThat(r.retryCount()).as("retry_count is reset").isEqualTo(0);
        assertThat(r.errorMessage()).isNull();
    }

    @Test
    void skipClearsTheBlockButLeavesThePoisonRowTerminal() {
        String group = id("skip-group");
        String head = id("skip-head");
        seedRow(head, OutboxItemType.EVENT, group, payload(head), 0, 0, Instant.now());
        statusFor.put(head, "BAD_REQUEST");
        var processor = processor(() -> true, config(1000, 3, true));
        invokePollOnce(processor);
        await(() -> !processor.blockedGroups().isEmpty());

        assertThat(processor.skipGroup(group)).isTrue();

        assertThat(processor.blockedGroups()).isEmpty();
        var r = row(head);
        assertThat(r.status()).as("skip leaves the poison row's terminal status untouched")
                .isEqualTo(OutboxStatus.BAD_REQUEST.code());
        assertThat(r.retryCount()).as("skip does not reset retry_count").isEqualTo(1);
    }

    @Test
    void unblockAndSkipAnswerFalseWhenTheGroupIsNotBlocked() {
        var processor = processor(() -> true, config(1000, 3, true));

        assertThat(processor.unblockGroup("never-touched")).isFalse();
        assertThat(processor.skipGroup("never-touched")).isFalse();
    }

    @Test
    void pauseAndResumeRoundTrip() {
        var processor = processor(() -> true, config(1000, 3, true));
        String group = id("pause-resume");

        assertThat(processor.pauseGroup(group)).isTrue();
        assertThat(processor.groupStates()).extracting(GroupStateManager.GroupInfo::status).containsExactly("PAUSED");
        assertThat(processor.resumeGroup(group)).isTrue();
        assertThat(processor.groupStates()).isEmpty();
    }

    // ── recovery tick (spec §4, §9) ──────────────────────────────────────────

    @Test
    void recoveryReturnsOldInProgressRowsButNotFreshOnesAndSkipsWhenNotLeader() {
        String stale = id("recovery-stale");
        String fresh = id("recovery-fresh");
        seedRow(stale, OutboxItemType.EVENT, null, payload(stale), 9, 0, Instant.now());
        seedRow(fresh, OutboxItemType.EVENT, null, payload(fresh), 9, 0, Instant.now());
        backdate(stale, Instant.now().minus(Duration.ofSeconds(30)));
        var shortThreshold = new OutboxProcessor.Config(100, 1000, Duration.ofSeconds(1), 10, true, 3,
                Duration.ofSeconds(60), Duration.ofSeconds(10), Duration.ofSeconds(5));

        var notLeader = processor(() -> false, shortThreshold);
        invokeRecoverOnce(notLeader);
        assertThat(row(stale).status()).as("non-leader recovery must not touch anything").isEqualTo(9);

        var leader = processor(() -> true, shortThreshold);
        invokeRecoverOnce(leader);
        assertThat(row(stale).status()).as("older than the threshold -> PENDING").isEqualTo(0);
        assertThat(row(fresh).status()).as("younger than the threshold -> untouched").isEqualTo(9);
    }

    private static void backdate(String rowId, Instant updatedAt) {
        try (var conn = DS.getConnection();
             var ps = conn.prepareStatement("UPDATE outbox_messages SET updated_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(updatedAt));
            ps.setString(2, rowId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // pollOnce/recoverOnce are package-private (test-only synchronous hooks,
    // same idiom as DispatchScheduler#poller()); called directly since this
    // test class shares the package.
    private static void invokePollOnce(OutboxProcessor processor) {
        processor.pollOnce();
    }

    private static void invokeRecoverOnce(OutboxProcessor processor) {
        processor.recoverOnce();
    }
}

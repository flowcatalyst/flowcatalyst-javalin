package io.flowcatalyst.router.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.observability.WarningStore;
import io.flowcatalyst.router.observability.Warnings;
import io.flowcatalyst.router.pool.QueuedMessage;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.queue.QueueMetrics;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// `POST /messages` / `POST /api/seed/messages` (`docs/spec/router.md` §9.1)
/// against a real Postgres `queue_messages` table — the same fixture
/// [io.flowcatalyst.router.queue.postgres.PostgresQueueTest] uses, since
/// these routes publish through the same [PostgresQueue].
class MessageRoutesTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final DataSource DS = TestPg.dataSource();

    /// Prefixed so alphabetical order is pinned regardless of what
    /// [#freshQueue] registers later in the same run.
    private static final String TAG = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String QUEUE_A = "a-queue-" + TAG;
    private static final String QUEUE_B = "b-queue-" + TAG;
    /// Registered but NOT a [io.flowcatalyst.router.queue.Publisher] — stands
    /// in for SQS/NATS, whose factories don't build one (spec: "SQS/NATS
    /// publishers are out of scope").
    private static final String QUEUE_C_NO_PUBLISHER = "c-queue-" + TAG;

    private static RouterManager manager;
    private static TestHttp http;
    /// A manager with zero registered queues — the "no publisher" 503 case.
    private static TestHttp noQueuesHttp;
    /// `manager == null` — the other provider-absent 503 case ([RouterApi.State]'s degrade rule).
    private static TestHttp bareHttp;

    @BeforeAll
    static void start() {
        PostgresQueue.initSchema(DS);

        manager = new RouterManager(new InFlightTracker(CLOCK), Warnings.NO_OP, CLOCK,
                cfg -> {
                    throw new UnsupportedOperationException("not exercised by these tests");
                });
        manager.registerConsumer(new PostgresQueue(DS, QUEUE_A, Duration.ofSeconds(30)));
        manager.registerConsumer(new PostgresQueue(DS, QUEUE_B, Duration.ofSeconds(30)));
        manager.registerConsumer(new NonPublishingConsumer(QUEUE_C_NO_PUBLISHER));
        var state = new RouterApi.State(manager, new InFlightTracker(CLOCK), new WarningStore(CLOCK), null, null,
                null, "test-version", "/router", null, null, null, null);
        http = TestHttp.routes(routes -> RouterApi.register(routes, state));

        var emptyManager = new RouterManager(new InFlightTracker(CLOCK), Warnings.NO_OP, CLOCK,
                cfg -> {
                    throw new UnsupportedOperationException("not exercised by these tests");
                });
        var noQueuesState = new RouterApi.State(emptyManager, new InFlightTracker(CLOCK), new WarningStore(CLOCK),
                null, null, null, "test-version", "/router", null, null, null, null);
        noQueuesHttp = TestHttp.routes(routes -> RouterApi.register(routes, noQueuesState));

        var bareState = new RouterApi.State(null, new InFlightTracker(CLOCK), new WarningStore(CLOCK), null, null,
                null, "test-version", "/router", null, null, null, null);
        bareHttp = TestHttp.routes(routes -> RouterApi.register(routes, bareState));
    }

    @AfterAll
    static void stop() {
        http.close();
        noQueuesHttp.close();
        bareHttp.close();
    }

    private static String freshQueue() {
        String name = "fresh-queue-" + UUID.randomUUID();
        manager.registerConsumer(new PostgresQueue(DS, name, Duration.ofSeconds(30)));
        return name;
    }

    private static JsonNode json(HttpResponse<String> r) {
        try {
            return Json.MAPPER.readTree(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + r.body(), e);
        }
    }

    private static long rowCount(String queueName) {
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM queue_messages WHERE queue_name = ?")) {
            ps.setString(1, queueName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Optional<Long> visibleAt(String queueName, String id) {
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT visible_at FROM queue_messages WHERE queue_name = ? AND id = ?")) {
            ps.setString(1, queueName);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Optional<Message> payload(String queueName, String id) {
        try (Connection conn = DS.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT payload FROM queue_messages WHERE queue_name = ? AND id = ?")) {
            ps.setString(1, queueName);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(Json.read(rs.getString(1), Message.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── POST /messages ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /messages inserts one row into queue_messages, visible now, with dispatch_mode defaulted to IMMEDIATE (spec §9.1, §7.3)")
    void publishMessageInsertsRow() {
        var queue = freshQueue();
        var body = Json.write(Map.of("pool_code", queue, "mediation_target", "https://example.test/hook"));

        var r = http.post("/router/messages", body);
        assertThat(r.statusCode()).as("201 on success").isEqualTo(201);

        var resp = json(r);
        String messageId = resp.get("message_id").asString();
        assertThat(messageId).isNotBlank();
        assertThat(resp.get("pool_code").asString()).isEqualTo(queue);
        assertThat(resp.get("queue_identifier").asString()).isEqualTo(queue);
        // Postgres publish returns m.ID as the broker id (§7.3 "Publish").
        assertThat(resp.get("broker_message_id").asString()).isEqualTo(messageId);

        // The row actually landed — not merely that the endpoint said 201.
        assertThat(rowCount(queue)).as("exactly one row for this fresh queue").isEqualTo(1);
        assertThat(visibleAt(queue, messageId)).hasValueSatisfying(
                v -> assertThat(v).isLessThanOrEqualTo(Instant.now().getEpochSecond()));

        var stored = payload(queue, messageId).orElseThrow();
        assertThat(stored.id()).isEqualTo(messageId);
        assertThat(stored.mediationTarget()).isEqualTo("https://example.test/hook");
        assertThat(stored.mediationType()).isEqualTo(MediationType.HTTP);
        // Load-bearing: Message#dispatchMode()'s generic default is
        // NEXT_ON_ERROR (DispatchMode#DEFAULT) — an omitted dispatch_mode on
        // THIS endpoint must resolve to IMMEDIATE explicitly (spec §9.1
        // "defaults HTTP / IMMEDIATE"), not fall through to the generic one.
        assertThat(stored.dispatchMode()).isEqualTo(DispatchMode.IMMEDIATE);
    }

    @Test
    @DisplayName("POST /messages honours an explicit dispatch_mode")
    void publishMessageHonoursExplicitDispatchMode() {
        var queue = freshQueue();
        var body = Json.write(Map.of("pool_code", queue, "mediation_target", "https://example.test/hook",
                "dispatch_mode", "BLOCK_ON_ERROR"));
        var r = http.post("/router/messages", body);
        assertThat(r.statusCode()).isEqualTo(201);
        String messageId = json(r).get("message_id").asString();
        var stored = payload(queue, messageId).orElseThrow();
        assertThat(stored.dispatchMode()).isEqualTo(DispatchMode.BLOCK_ON_ERROR);
    }

    @Test
    @DisplayName("POST /messages 422s when pool_code is missing")
    void publishMessageMissingPoolCode() {
        var body = Json.write(Map.of("mediation_target", "https://example.test/hook"));
        var r = http.post("/router/messages", body);
        assertThat(r.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("POST /messages 422s when mediation_target is missing")
    void publishMessageMissingMediationTarget() {
        var body = Json.write(Map.of("pool_code", QUEUE_A));
        var r = http.post("/router/messages", body);
        assertThat(r.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("POST /messages falls back to the alphabetically-first queue for an unknown pool_code (spec §5 #61)")
    void publishMessageUnknownPoolCodeFallsBackToFirstQueue() {
        var body = Json.write(Map.of("pool_code", "no-such-pool-" + UUID.randomUUID(),
                "mediation_target", "https://example.test/hook"));
        var r = http.post("/router/messages", body);
        assertThat(r.statusCode()).isEqualTo(201);
        // QUEUE_A < QUEUE_B < QUEUE_C_NO_PUBLISHER by construction (the "a-"/"b-"/"c-" prefixes).
        assertThat(json(r).get("queue_identifier").asString()).isEqualTo(QUEUE_A);
    }

    @Test
    @DisplayName("POST /messages 503s when the resolved queue's backend has no Publisher (SQS/NATS, out of scope)")
    void publishMessageNoPublisherForResolvedQueue() {
        var body = Json.write(Map.of("pool_code", QUEUE_C_NO_PUBLISHER, "mediation_target", "https://example.test/hook"));
        var r = http.post("/router/messages", body);
        assertThat(r.statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("POST /messages 503s when no queue is registered at all")
    void publishMessageNoQueuesConfigured() {
        var body = Json.write(Map.of("pool_code", "anything", "mediation_target", "https://example.test/hook"));
        var r = noQueuesHttp.post("/router/messages", body);
        assertThat(r.statusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("POST /messages 503s when no manager is wired at all")
    void publishMessageNoManager() {
        var body = Json.write(Map.of("pool_code", "anything", "mediation_target", "https://example.test/hook"));
        var r = bareHttp.post("/router/messages", body);
        assertThat(r.statusCode()).isEqualTo(503);
    }

    // ── POST /api/seed/messages ──────────────────────────────────────────

    @Test
    @DisplayName("POST /api/seed/messages publishes `count` IMMEDIATE rows at the default mock target (spec §9.1, §5 #57)")
    void seedMessagesPublishesCountRows() {
        var queue = freshQueue();
        var body = Json.write(Map.of("pool_code", queue, "count", 25));

        var r = http.post("/router/api/seed/messages", body);
        assertThat(r.statusCode()).isEqualTo(200);
        var resp = json(r);
        assertThat(resp.get("published").asLong()).isEqualTo(25);
        assertThat(resp.get("queue_identifier").asString()).isEqualTo(queue);

        // The rows actually landed — the response's own `published` count
        // would say 25 even if the batch silently dropped one (see the
        // publishBatch-skips-the-last-element mutant in the report).
        assertThat(rowCount(queue)).isEqualTo(25);
    }

    @Test
    @DisplayName("POST /api/seed/messages defaults the mediation target to the fast mock")
    void seedMessagesDefaultsTarget() {
        var queue = freshQueue();
        var body = Json.write(Map.of("pool_code", queue, "count", 1));
        var r = http.post("/router/api/seed/messages", body);
        assertThat(r.statusCode()).isEqualTo(200);

        try (Connection conn = DS.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM queue_messages WHERE queue_name = ?")) {
            ps.setString(1, queue);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                var stored = payload(queue, rs.getString(1)).orElseThrow();
                assertThat(stored.mediationTarget()).isEqualTo("https://localhost:8080/api/test/fast");
                assertThat(stored.dispatchMode()).isEqualTo(DispatchMode.IMMEDIATE);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("POST /api/seed/messages 422s when count is 0")
    void seedMessagesCountTooLow() {
        var body = Json.write(Map.of("pool_code", QUEUE_A, "count", 0));
        var r = http.post("/router/api/seed/messages", body);
        assertThat(r.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("POST /api/seed/messages 422s when count is 10001")
    void seedMessagesCountTooHigh() {
        var body = Json.write(Map.of("pool_code", QUEUE_A, "count", 10_001));
        var r = http.post("/router/api/seed/messages", body);
        assertThat(r.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("POST /api/seed/messages 422s when pool_code is missing")
    void seedMessagesMissingPoolCode() {
        var body = Json.write(Map.of("count", 5));
        var r = http.post("/router/api/seed/messages", body);
        assertThat(r.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("POST /api/seed/messages 503s when no queue is registered at all")
    void seedMessagesNoQueuesConfigured() {
        var body = Json.write(Map.of("pool_code", "anything", "count", 5));
        var r = noQueuesHttp.post("/router/api/seed/messages", body);
        assertThat(r.statusCode()).isEqualTo(503);
    }

    /// Registered but implements no [io.flowcatalyst.router.queue.Publisher]
    /// — stands in for the SQS/NATS backends this unit deliberately leaves
    /// unpublishable.
    private static final class NonPublishingConsumer implements Consumer {
        private final String id;

        NonPublishingConsumer(String id) {
            this.id = id;
        }

        @Override
        public String identifier() {
            return id;
        }

        @Override
        public PollResult poll(int max) {
            return PollResult.empty();
        }

        @Override
        public boolean ack(QueuedMessage message) {
            return true;
        }

        @Override
        public void defer(QueuedMessage message, Duration delay) {
            nack(message, delay);
        }

        @Override
        public void nack(QueuedMessage message, Duration delay) {
        }

        @Override
        public boolean honoursDelayedReturn() {
            return true;
        }

        @Override
        public Optional<QueueMetrics> metrics() {
            return Optional.empty();
        }

        @Override
        public void close() {
        }
    }
}

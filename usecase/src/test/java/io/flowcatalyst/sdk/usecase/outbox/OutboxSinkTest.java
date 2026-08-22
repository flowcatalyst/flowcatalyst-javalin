package io.flowcatalyst.sdk.usecase.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxSinkTest {

    record OrderPlaced(EventMetadata metadata, String orderId, int total) implements DomainEvent {
        @Override public Object data() { return new Data(orderId, total); }
        private record Data(String orderId, int total) {}
        @Override public String messageGroup() { return "orders:order:" + orderId; }
    }

    record PlaceOrder(String orderId) {}

    @Test
    void writesTheCrossSdkPayloadShape() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE outbox_messages (
                      id VARCHAR(26) PRIMARY KEY, type VARCHAR(30), message_group VARCHAR(200), payload CLOB,
                      status SMALLINT, retry_count SMALLINT, created_at TIMESTAMP, updated_at TIMESTAMP,
                      client_id VARCHAR(17), payload_size INT)""");
        }
        var mapper = new ObjectMapper();
        var sink = new OutboxSink(new OutboxSink.Config(null, "clt_123", true), mapper);

        var ec = ExecutionContext.withCorrelation("prn_1", "corr-1");
        var event = new OrderPlaced(EventMetadata.of(ec, "shop:sales:order:placed", "shop:sales", "sales.order.ord_1"), "ord_1", 42);

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            var tx = DbTx.wrapForBootstrap(c);
            sink.writeEvent(tx, event);
            sink.writeAudit(tx, event, new PlaceOrder("ord_1"));
            c.commit();
        }

        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT type, message_group, payload, status, client_id, payload_size FROM outbox_messages ORDER BY type")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("type")).isEqualTo("AUDIT_LOG");
            JsonNode audit = mapper.readTree(rs.getString("payload"));
            assertThat(audit.get("entity_type").asText()).isEqualTo("Order");
            assertThat(audit.get("entity_id").asText()).isEqualTo("ord_1");
            assertThat(audit.get("operation").asText()).isEqualTo("PlaceOrder");
            assertThat(audit.get("operation_json").get("orderId").asText()).isEqualTo("ord_1");
            assertThat(audit.get("principal_id").asText()).isEqualTo("prn_1");
            assertThat(audit.get("performed_at").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z");

            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("type")).isEqualTo("EVENT");
            assertThat(rs.getString("message_group")).isEqualTo("orders:order:ord_1");
            assertThat(rs.getInt("status")).isZero();
            assertThat(rs.getString("client_id")).isEqualTo("clt_123");
            String payload = rs.getString("payload");
            assertThat(rs.getInt("payload_size")).isEqualTo(payload.getBytes(StandardCharsets.UTF_8).length);
            JsonNode json = mapper.readTree(payload);
            assertThat(json.get("event_type").asText()).isEqualTo("shop:sales:order:placed");
            assertThat(json.get("spec_version").asText()).isEqualTo("1.0");
            assertThat(json.get("subject").asText()).isEqualTo("sales.order.ord_1");
            assertThat(json.get("deduplication_id").asText()).isEqualTo("shop:sales:order:placed-" + event.eventId());
            assertThat(json.get("correlation_id").asText()).isEqualTo("corr-1");
            assertThat(json.get("causation_id").asText()).isEmpty();
            assertThat(json.get("message_group").asText()).isEqualTo("orders:order:ord_1");
            assertThat(json.get("data").get("orderId").asText()).isEqualTo("ord_1");
            assertThat(json.get("data").get("total").asInt()).isEqualTo(42);
            assertThat(json.get("context_data").get(0).get("key").asText()).isEqualTo("principalId");
            assertThat(json.get("context_data").get(1).get("value").asText()).isEqualTo("Order");
            // keys are sorted, as Go's map marshalling does
            assertThat(payload).startsWith("{\"causation_id\"");
        }
    }
}

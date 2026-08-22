package io.flowcatalyst.sdk.usecase.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Sink;
import io.flowcatalyst.sdk.usecase.jdbc.SinkSupport;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// The consumer-application [Sink]: writes domain events (and, when enabled,
/// audit logs) to the application's `outbox_messages` table, from where the
/// FlowCatalyst outbox processor forwards them to the platform API.
///
/// The row and payload shapes are the cross-SDK wire contract (Go, TypeScript,
/// Laravel, Java all produce the same bytes): snake_case keys, sorted, status
/// `0` (pending), a 13-character TSID row id.
public final class OutboxSink implements Sink {

    /// @param tableName    the outbox table; default `outbox_messages`
    /// @param clientId     tenant scope for the rows; may be `null`
    /// @param auditEnabled also write `AUDIT_LOG` rows for every event. The
    ///                     platform always audits its control-plane writes;
    ///                     consumer apps should enable this only for admin /
    ///                     human-initiated operations, not every transactional event.
    public record Config(String tableName, String clientId, boolean auditEnabled) {
        public static final String DEFAULT_TABLE = "outbox_messages";

        public Config {
            if (tableName == null || tableName.isBlank()) {
                tableName = DEFAULT_TABLE;
            }
        }

        public static Config defaults() {
            return new Config(DEFAULT_TABLE, null, false);
        }
    }

    private static final DateTimeFormatter PERFORMED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXX").withZone(ZoneOffset.UTC);

    private final Config config;
    private final ObjectMapper mapper;
    private final ObjectWriter writer;
    private final String insertSql;

    public OutboxSink(Config config, ObjectMapper mapper) {
        this.config = Objects.requireNonNull(config, "config");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.writer = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.insertSql = "INSERT INTO " + config.tableName()
                + " (id, type, message_group, payload, status, retry_count, created_at, updated_at, client_id, payload_size)"
                + " VALUES (?, ?, ?, ?, 0, 0, NOW(), NOW(), ?, ?)";
    }

    @Override
    public void writeEvent(DbTx tx, DomainEvent event) throws SQLException {
        insert(tx, "EVENT", event.messageGroup(), eventPayload(event));
    }

    @Override
    public void writeAudit(DbTx tx, DomainEvent event, Object command) throws SQLException {
        if (!config.auditEnabled()) return;
        insert(tx, "AUDIT_LOG", event.messageGroup(), auditPayload(event, command));
    }

    private void insert(DbTx tx, String type, String messageGroup, String payload) throws SQLException {
        try (PreparedStatement ps = tx.connection().prepareStatement(insertSql)) {
            ps.setString(1, Tsid.generate());
            ps.setString(2, type);
            ps.setString(3, SinkSupport.nullIfEmpty(messageGroup));
            ps.setString(4, payload);
            ps.setString(5, SinkSupport.nullIfEmpty(config.clientId()));
            ps.setInt(6, payload.getBytes(StandardCharsets.UTF_8).length);
            ps.executeUpdate();
        }
    }

    /// The snake_case event payload the outbox processor parses. `data` is
    /// re-read as a JSON object (non-object data becomes `{}`).
    private String eventPayload(DomainEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event_type", event.eventType());
        payload.put("spec_version", event.specVersion());
        payload.put("source", event.source());
        payload.put("subject", event.subject());
        payload.put("data", dataObject(event));
        payload.put("correlation_id", SinkSupport.orEmpty(event.correlationId()));
        payload.put("causation_id", SinkSupport.orEmpty(event.causationId()));
        payload.put("deduplication_id", event.eventType() + "-" + event.eventId());
        payload.put("message_group", SinkSupport.orEmpty(event.messageGroup()));
        payload.put("context_data", SinkSupport.contextData(event));
        return toJson(payload);
    }

    private String auditPayload(DomainEvent event, Object command) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("entity_type", EventConventions.extractAggregateType(event.subject()));
        payload.put("entity_id", EventConventions.extractEntityId(event.subject()));
        payload.put("operation", SinkSupport.commandName(command));
        payload.put("operation_json", mapper.valueToTree(command));
        payload.put("principal_id", SinkSupport.orEmpty(event.principalId()));
        payload.put("performed_at", PERFORMED_AT.format(SinkSupport.eventTime(event)));
        return toJson(payload);
    }

    private Map<String, Object> dataObject(DomainEvent event) {
        Object data = event.data();
        if (data == null) return Map.of();
        JsonNode node = mapper.valueToTree(data);
        if (!node.isObject()) return Map.of();
        return mapper.convertValue(node, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }

    private String toJson(Object value) {
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise outbox payload", e);
        }
    }
}

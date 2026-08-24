package io.flowcatalyst.platform.shared.platformsink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Sink;
import io.flowcatalyst.sdk.usecase.jdbc.SinkSupport;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/// The platform's own [Sink]: where a consumer app's unit of work writes to
/// `outbox_messages` for eventual forwarding, the platform writes domain
/// events and audit logs *directly* to `msg_events` and `aud_logs`. The
/// platform IS the platform — there is no outbox to hop through.
///
/// Row shapes match the Go `platformsink` byte-for-byte where it matters:
///
///   - `msg_events`: `deduplication_id = type + "-" + eventId`,
///     `context_data = [{key: principalId, value}, {key: aggregateType, value}]`,
///     `created_at = now()`, `client_id = NULL`. Plain `INSERT` — the table is
///     partitioned by `created_at` and the dedup index is composite, so a
///     duplicate surfaces as a transaction failure rather than `ON CONFLICT`.
///   - `aud_logs`: `id = aud_{tsid}`, `entity_type` / `entity_id` derived from
///     the event subject, `operation` = the command's simple class name,
///     `operation_json` = the command as JSON, `performed_at` = event time.
public final class PlatformSink implements Sink {

    private static final String INSERT_EVENT = """
            INSERT INTO msg_events
                (id, spec_version, type, source, subject,
                 time, data, correlation_id, causation_id,
                 deduplication_id, message_group, client_id,
                 context_data, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    private static final String INSERT_AUDIT = """
            INSERT INTO aud_logs
                (id, entity_type, entity_id, operation,
                 operation_json, principal_id, application_id,
                 client_id, performed_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """;

    private final ObjectMapper mapper;

    public PlatformSink(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void writeEvent(DbTx tx, DomainEvent event) throws SQLException {
        String data = dataJson(event);
        String contextData = toJson(SinkSupport.contextData(event));
        String dedupId = event.eventType() + "-" + event.eventId();

        try (PreparedStatement ps = tx.connection().prepareStatement(INSERT_EVENT)) {
            ps.setString(1, event.eventId());
            ps.setString(2, event.specVersion());
            ps.setString(3, event.eventType());
            ps.setString(4, event.source());
            ps.setString(5, event.subject());
            ps.setObject(6, utc(SinkSupport.eventTime(event)));
            ps.setString(7, data);
            ps.setString(8, SinkSupport.nullIfEmpty(event.correlationId()));
            ps.setString(9, SinkSupport.nullIfEmpty(event.causationId()));
            ps.setString(10, dedupId);
            ps.setString(11, SinkSupport.nullIfEmpty(event.messageGroup()));
            ps.setNull(12, Types.VARCHAR); // client_id
            ps.setString(13, contextData);
            ps.setObject(14, utc(Instant.now()));
            ps.executeUpdate();
        }
    }

    @Override
    public void writeAudit(DbTx tx, DomainEvent event, Object command) throws SQLException {
        String commandJson = toJson(command);
        try (PreparedStatement ps = tx.connection().prepareStatement(INSERT_AUDIT)) {
            ps.setString(1, EntityType.AUDIT_LOG.generate());
            ps.setString(2, EventConventions.extractAggregateType(event.subject()));
            ps.setString(3, EventConventions.extractEntityId(event.subject()));
            ps.setString(4, SinkSupport.commandName(command));
            ps.setString(5, commandJson);
            // The actor always comes from the metadata directly, never from
            // event.principalId(): metadata().principalId() is the single
            // source of truth for who acted, and DomainEventContractTest
            // enforces that no DomainEvent record may declare a "principalId"
            // component that would shadow DomainEvent#principalId()'s default
            // and silently substitute the event's subject for its actor.
            ps.setString(6, SinkSupport.nullIfEmpty(event.metadata().principalId()));
            ps.setNull(7, Types.VARCHAR); // application_id
            ps.setNull(8, Types.VARCHAR); // client_id
            ps.setObject(9, utc(SinkSupport.eventTime(event)));
            ps.executeUpdate();
        }
    }

    private String dataJson(DomainEvent event) {
        Object data = event.data();
        if (data == null) return "{}";
        String json = toJson(data);
        return json.isEmpty() || "null".equals(json) ? "{}" : json;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise event/audit payload", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

package io.flowcatalyst.platform.eventtype;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.MsgEventTypeSpecVersions;
import io.flowcatalyst.db.generated.tables.MsgEventTypes;
import io.flowcatalyst.db.generated.tables.records.MsgEventTypeSpecVersionsRecord;
import io.flowcatalyst.db.generated.tables.records.MsgEventTypesRecord;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPES;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENT_TYPE_SPEC_VERSIONS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `msg_event_types` + `msg_event_type_spec_versions` via jOOQ. Reads hydrate
/// the spec versions in one extra query; writes happen only on the unit of
/// work's transaction ([Persist]). Pure CRUD — no domain decisions live here.
public final class EventTypeRepository implements Persist<EventType> {

    private static final MsgEventTypes T = MSG_EVENT_TYPES;
    private static final MsgEventTypeSpecVersions SV = MSG_EVENT_TYPE_SPEC_VERSIONS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public EventTypeRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Equality filters for [#findWithFilters]; `null` = no filter on that column.
    public record ListFilter(String application, String status, String subdomain, String aggregate) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<EventType> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    public Optional<EventType> findByCode(String code) {
        return findOne(T.CODE.eq(code));
    }

    /// Every event type whose first code segment is `applicationCode`, by code.
    public List<EventType> findByApplication(String applicationCode) {
        return findMany(T.APPLICATION.eq(applicationCode));
    }

    /// Event types matching every non-null filter, by code.
    public List<EventType> findWithFilters(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.application() != null) where = where.and(T.APPLICATION.eq(f.application()));
        if (f.status() != null) where = where.and(T.STATUS.eq(f.status()));
        if (f.subdomain() != null) where = where.and(T.SUBDOMAIN.eq(f.subdomain()));
        if (f.aggregate() != null) where = where.and(T.AGGREGATE.eq(f.aggregate()));
        return findMany(where);
    }

    private Optional<EventType> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional()
                .map(row -> toEntity(row, specVersionsFor(List.of(row.getId())).getOrDefault(row.getId(), List.of())));
    }

    private List<EventType> findMany(Condition where) {
        var rows = dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var versionsById = specVersionsFor(rows.getValues(T.ID));
        return List.copyOf(rows.map(row -> toEntity(row, versionsById.getOrDefault(row.getId(), List.of()))));
    }

    /// Spec versions for many event types in one query, each list ordered by version.
    private Map<String, List<SpecVersion>> specVersionsFor(List<String> eventTypeIds) {
        return dsl.selectFrom(SV)
                .where(SV.EVENT_TYPE_ID.in(eventTypeIds))
                .orderBy(SV.EVENT_TYPE_ID.asc(), SV.VERSION.asc())
                .fetch().stream()
                .collect(groupingBy(MsgEventTypeSpecVersionsRecord::getEventTypeId,
                        mapping(EventTypeRepository::toSpecVersion, toList())));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row and every spec version `ON CONFLICT (id)`. `created_by`
    /// and `created_at` are written once and never updated; `updated_at` is
    /// stamped `now()` here, not taken from the aggregate (spec §9).
    @Override
    public void persist(EventType et, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        OffsetDateTime now = utc(Instant.now());

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CODE, et.code());
        row.put(T.NAME, et.name());
        row.put(T.DESCRIPTION, et.description());
        row.put(T.STATUS, et.status().name());
        row.put(T.SOURCE, et.source().name());
        row.put(T.CLIENT_SCOPED, et.clientScoped());
        row.put(T.APPLICATION, et.application());
        row.put(T.SUBDOMAIN, et.subdomain());
        row.put(T.AGGREGATE, et.aggregate());
        row.put(T.UPDATED_AT, now);
        txDsl.insertInto(T)
                .set(T.ID, et.id())
                .set(T.CREATED_BY, et.createdBy())
                .set(T.CREATED_AT, utc(et.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();

        for (SpecVersion sv : et.specVersions()) {
            var svRow = new LinkedHashMap<Field<?>, Object>();
            svRow.put(SV.SCHEMA_CONTENT, toJsonb(sv.schemaContent()));
            svRow.put(SV.SCHEMA_TYPE, sv.schemaType().name());
            svRow.put(SV.STATUS, sv.status().name());
            svRow.put(SV.UPDATED_AT, now);
            txDsl.insertInto(SV)
                    .set(SV.ID, sv.id())
                    .set(SV.EVENT_TYPE_ID, sv.eventTypeId())
                    .set(SV.VERSION, sv.version())
                    .set(SV.MIME_TYPE, sv.mimeType())
                    .set(SV.CREATED_AT, utc(sv.createdAt()))
                    .set(svRow)
                    .onConflict(SV.ID).doUpdate().set(svRow)
                    .execute();
        }
    }

    /// Removes the spec versions, then the event type.
    @Override
    public void delete(EventType et, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(SV).where(SV.EVENT_TYPE_ID.eq(et.id())).execute();
        txDsl.deleteFrom(T).where(T.ID.eq(et.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static EventType toEntity(MsgEventTypesRecord row, List<SpecVersion> specVersions) {
        return new EventType(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                specVersions,
                EventTypeStatus.parse(row.getStatus()),
                EventTypeSource.parse(row.getSource()),
                row.getClientScoped(),
                row.getApplication(),
                row.getSubdomain(),
                row.getAggregate(),
                EventTypeCode.eventNameOf(row.getCode()),
                null, // clientId: not a column (spec §1)
                row.getCreatedBy(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static SpecVersion toSpecVersion(MsgEventTypeSpecVersionsRecord row) {
        return new SpecVersion(
                row.getId(),
                row.getEventTypeId(),
                row.getVersion(),
                row.getMimeType(),
                fromJsonb(row.getSchemaContent()),
                SchemaType.parse(row.getSchemaType()),
                SpecVersionStatus.parse(row.getStatus()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static JSONB toJsonb(JsonNode node) {
        return node == null ? null : JSONB.jsonb(Json.write(node));
    }

    private static JsonNode fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isEmpty()) return null;
        try {
            return Json.MAPPER.readTree(jsonb.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("msg_event_type_spec_versions.schema_content is not valid JSON", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

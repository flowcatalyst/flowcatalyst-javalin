package io.flowcatalyst.platform.openapispecs;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.AppApplicationOpenapiSpecs;
import io.flowcatalyst.db.generated.tables.records.AppApplicationOpenapiSpecsRecord;
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
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATION_OPENAPI_SPECS;

/// `app_application_openapi_specs` via jOOQ (spec §5). Reads on the pool,
/// writes only on the unit of work's transaction ([Persist]). The two JSON
/// columns are foreign shapes: `spec` is the document verbatim,
/// `change_notes` is read leniently (`NULL` / partial keys → defaults).
public final class OpenApiSpecRepository implements Persist<OpenApiSpec> {

    private static final AppApplicationOpenapiSpecs T = APP_APPLICATION_OPENAPI_SPECS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public OpenApiSpecRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<OpenApiSpec> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The application's single `CURRENT` row, if any spec was ever synced.
    public Optional<OpenApiSpec> findCurrentByApplication(String applicationId) {
        return findOne(T.APPLICATION_ID.eq(applicationId).and(T.STATUS.eq(SpecStatus.CURRENT.name())));
    }

    /// Every spec of the application: the `CURRENT` row first, then archived
    /// versions newest first.
    public List<OpenApiSpec> findAllByApplication(String applicationId) {
        return List.copyOf(dsl.selectFrom(T)
                .where(T.APPLICATION_ID.eq(applicationId))
                .orderBy(DSL.when(T.STATUS.eq(SpecStatus.CURRENT.name()), 0).otherwise(1).asc(), T.SYNCED_AT.desc(), T.ID.desc())
                .fetch()
                .map(OpenApiSpecRepository::toEntity));
    }

    /// Whether ANY row (`CURRENT` or `ARCHIVED`) already has `(application, version)`.
    public boolean existsByApplicationAndVersion(String applicationId, String version) {
        return dsl.fetchExists(dsl.selectOne().from(T)
                .where(T.APPLICATION_ID.eq(applicationId)).and(T.VERSION.eq(version)));
    }

    private Optional<OpenApiSpec> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(OpenApiSpecRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`; `created_at` is written once.
    @Override
    public void persist(OpenApiSpec s, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.APPLICATION_ID, s.applicationId());
        row.put(T.VERSION, s.version());
        row.put(T.STATUS, s.status().name());
        row.put(T.SPEC, toJsonb(s.spec()));
        row.put(T.SPEC_HASH, s.specHash());
        row.put(T.CHANGE_NOTES, s.changeNotes() == null ? null : JSONB.jsonb(Json.write(s.changeNotes())));
        row.put(T.CHANGE_NOTES_TEXT, s.changeNotesText());
        row.put(T.SYNCED_AT, utc(s.syncedAt()));
        row.put(T.SYNCED_BY, s.syncedBy());
        row.put(T.UPDATED_AT, utc(s.updatedAt()));
        txDsl.insertInto(T)
                .set(T.ID, s.id())
                .set(T.CREATED_AT, utc(s.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(OpenApiSpec s, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(s.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static OpenApiSpec toEntity(AppApplicationOpenapiSpecsRecord row) {
        return new OpenApiSpec(
                row.getId(),
                row.getApplicationId(),
                row.getVersion(),
                SpecStatus.parse(row.getStatus()),
                fromJsonb(row.getSpec()),
                row.getSpecHash(),
                changeNotes(row.getChangeNotes()),
                row.getChangeNotesText(),
                row.getSyncedAt().toInstant(),
                row.getSyncedBy(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static JSONB toJsonb(JsonNode node) {
        return JSONB.jsonb(Json.write(node));
    }

    private static JsonNode fromJsonb(JSONB jsonb) {
        try {
            return Json.MAPPER.readTree(jsonb.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("stored spec is not valid JSON", e);
        }
    }

    /// `NULL`, a JSON `null` or a partial object all read; missing keys are defaults.
    private static ChangeNotes changeNotes(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) return null;
        try {
            JsonNode node = Json.MAPPER.readTree(jsonb.data());
            return node.isNull() ? null : Json.MAPPER.treeToValue(node, ChangeNotes.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored change_notes is not valid JSON", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

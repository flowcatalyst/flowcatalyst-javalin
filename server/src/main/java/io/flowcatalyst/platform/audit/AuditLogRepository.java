package io.flowcatalyst.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.AudLogs;
import io.flowcatalyst.db.generated.tables.IamPrincipals;
import io.flowcatalyst.platform.shared.json.Json;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.AUD_LOGS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;

/// `aud_logs` via jOOQ — reads only (spec §9): the rows are written by the
/// unit-of-work sink, never here. Every read joins `iam_principals` for the
/// acting principal's name. Pure CRUD — no domain decisions live here.
public final class AuditLogRepository {

    private static final AudLogs T = AUD_LOGS;
    private static final IamPrincipals P = IAM_PRINCIPALS;

    /// Cursor read: `limit <= 0` or `> MAX` falls back to the default (spec §7).
    static final int CURSOR_MAX_LIMIT = 501;
    static final int CURSOR_DEFAULT_LIMIT = 101;
    /// Filtered read: same guard family (spec §7).
    static final int FILTER_MAX_LIMIT = 500;
    static final int FILTER_DEFAULT_LIMIT = 100;
    /// Facet read: same guard family (spec §7).
    static final int FACET_MAX_LIMIT = 1000;
    static final int FACET_DEFAULT_LIMIT = 200;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public AuditLogRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Filters for the offset-style read [#findWithFilters] (spec §5);
    /// `null` = no filter on that column. `since` / `until` are inclusive.
    public record ListFilter(String entityType, String entityId, String principalId, String clientId,
                             Instant since, Instant until) {
    }

    /// Filters for the keyset read [#findWithCursor] (spec §3); `null` = no
    /// filter, and an empty id list is no filter either.
    public record CursorFilter(String entityType, String entityId, String principalId, String operation,
                               List<String> applicationIds, List<String> clientIds) {
        public CursorFilter {
            applicationIds = applicationIds == null ? List.of() : List.copyOf(applicationIds);
            clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        }
    }

    /// The closed set of columns a facet may be taken over (spec §6).
    public enum Facet {
        ENTITY_TYPE(T.ENTITY_TYPE),
        OPERATION(T.OPERATION),
        APPLICATION_ID(T.APPLICATION_ID),
        CLIENT_ID(T.CLIENT_ID);

        private final Field<String> column;

        Facet(Field<String> column) {
            this.column = column;
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<AuditLog> findById(String id) {
        return dsl.select(T.fields()).select(P.NAME).from(T).leftJoin(P).on(P.ID.eq(T.PRINCIPAL_ID))
                .where(T.ID.eq(id))
                .fetchOptional()
                .map(AuditLogRepository::toEntity);
    }

    /// Up to `limit` entries matching every filter, newest first
    /// (`performed_at DESC, id DESC`), strictly after `after` when given.
    /// The caller over-fetches by one to learn whether a next page exists.
    public List<AuditLog> findWithCursor(CursorFilter f, AuditLogCursor after, int limit) {
        Condition where = DSL.noCondition();
        if (f.entityType() != null) where = where.and(T.ENTITY_TYPE.eq(f.entityType()));
        if (f.entityId() != null) where = where.and(T.ENTITY_ID.eq(f.entityId()));
        if (f.principalId() != null) where = where.and(T.PRINCIPAL_ID.eq(f.principalId()));
        if (f.operation() != null) where = where.and(T.OPERATION.eq(f.operation()));
        if (!f.applicationIds().isEmpty()) where = where.and(T.APPLICATION_ID.in(f.applicationIds()));
        if (!f.clientIds().isEmpty()) where = where.and(T.CLIENT_ID.in(f.clientIds()));
        if (after != null) {
            where = where.and(DSL.row(T.PERFORMED_AT, T.ID).lt(after.performedAt().atOffset(ZoneOffset.UTC), after.id()));
        }
        return dsl.select(T.fields()).select(P.NAME).from(T).leftJoin(P).on(P.ID.eq(T.PRINCIPAL_ID))
                .where(where)
                .orderBy(T.PERFORMED_AT.desc(), T.ID.desc())
                .limit(guard(limit, CURSOR_MAX_LIMIT, CURSOR_DEFAULT_LIMIT))
                .fetch(AuditLogRepository::toEntity);
    }

    /// Entries matching every non-null filter, newest first (`performed_at DESC`),
    /// `offset` rows in, at most `limit` rows.
    public List<AuditLog> findWithFilters(ListFilter f, int limit, int offset) {
        Condition where = DSL.noCondition();
        if (f.entityType() != null) where = where.and(T.ENTITY_TYPE.eq(f.entityType()));
        if (f.entityId() != null) where = where.and(T.ENTITY_ID.eq(f.entityId()));
        if (f.principalId() != null) where = where.and(T.PRINCIPAL_ID.eq(f.principalId()));
        if (f.clientId() != null) where = where.and(T.CLIENT_ID.eq(f.clientId()));
        if (f.since() != null) where = where.and(T.PERFORMED_AT.ge(f.since().atOffset(ZoneOffset.UTC)));
        if (f.until() != null) where = where.and(T.PERFORMED_AT.le(f.until().atOffset(ZoneOffset.UTC)));
        return dsl.select(T.fields()).select(P.NAME).from(T).leftJoin(P).on(P.ID.eq(T.PRINCIPAL_ID))
                .where(where)
                .orderBy(T.PERFORMED_AT.desc())
                .limit(guard(limit, FILTER_MAX_LIMIT, FILTER_DEFAULT_LIMIT))
                .offset(Math.max(offset, 0))
                .fetch(AuditLogRepository::toEntity);
    }

    /// The distinct non-null values of one facet column, ascending, at most `limit`.
    public List<String> distinctValues(Facet facet, int limit) {
        return dsl.selectDistinct(facet.column).from(T)
                .where(facet.column.isNotNull())
                .orderBy(facet.column.asc())
                .limit(guard(limit, FACET_MAX_LIMIT, FACET_DEFAULT_LIMIT))
                .fetch(facet.column);
    }

    /// Out-of-range limits are corrected, not rejected (spec §7, open question 4).
    private static int guard(int limit, int max, int fallback) {
        return limit <= 0 || limit > max ? fallback : limit;
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static AuditLog toEntity(Record row) {
        return new AuditLog(
                row.get(T.ID),
                row.get(T.ENTITY_TYPE),
                row.get(T.ENTITY_ID),
                row.get(T.OPERATION),
                fromJsonb(row.get(T.OPERATION_JSON)),
                row.get(T.PRINCIPAL_ID),
                row.get(P.NAME),
                row.get(T.APPLICATION_ID),
                row.get(T.CLIENT_ID),
                row.get(T.PERFORMED_AT).toInstant());
    }

    private static JsonNode fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isEmpty()) return null;
        try {
            return Json.MAPPER.readTree(jsonb.data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("aud_logs.operation_json is not valid JSON", e);
        }
    }
}

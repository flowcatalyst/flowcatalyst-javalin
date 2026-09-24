package io.flowcatalyst.platform.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.db.generated.tables.AudLogs;
import io.flowcatalyst.db.generated.tables.IamPrincipals;
import io.flowcatalyst.db.generated.tables.records.AudLogsRecord;
import io.flowcatalyst.platform.shared.apicommon.KeysetCursor;
import io.flowcatalyst.platform.shared.json.Json;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.SelectOnConditionStep;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import static io.flowcatalyst.db.generated.Tables.AUD_LOGS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;

/// `aud_logs` via jOOQ — reads only (spec §9): the rows are written by the
/// unit-of-work sink, never here — plus [#redactExisting], a **temporary**
/// in-place update of already-stored rows (`docs/spec/audit-redaction.md`
/// "Temporary: redact existing rows"), to be removed once every row
/// written before the source-side redaction landed has been swept. Every
/// read joins `iam_principals` for the acting principal's name. Otherwise
/// pure CRUD — no domain decisions live here.
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

    // ── Writes (infra ingest — no unit of work, sdk-ingest spec §1/§4.3) ────

    /// One batch insert for `POST /api/audit-logs/batch` (spec §4.3): a
    /// plain `INSERT` (no `ON CONFLICT` — ids are freshly minted TSIDs, so a
    /// collision is a genuine fault, not a retried duplicate) wrapped in a
    /// transaction so a mid-batch failure rolls the whole batch back rather
    /// than leaving a partial prefix committed. One JDBC batch, one round
    /// trip; empty input is a no-op.
    public void insertBatch(List<AuditLog> logs) {
        if (logs.isEmpty()) return;
        dsl.transaction(cfg -> {
            var txDsl = DSL.using(cfg);
            var queries = logs.stream().map(l -> insertQuery(txDsl, l)).toList();
            txDsl.batch(queries).execute();
        });
    }

    /// Batch size for [#redactExisting] (spec "Temporary: redact existing rows").
    static final int REDACT_BATCH_SIZE = 500;

    /// **Temporary** (`docs/spec/audit-redaction.md` "Temporary: redact
    /// existing rows from the dashboard"): walks every row in `id` order
    /// (TSIDs are lexically monotonic), in batches of [#REDACT_BATCH_SIZE],
    /// applies `redactor` — given the row's `operation` and its current
    /// `operation_json` — and updates only rows whose JSON actually changed
    /// (`JsonNode#equals` is deep value equality, not reference equality),
    /// so a second run redacts nothing. Each changed row is its own
    /// `UPDATE`; the caller decides what counts as "scanned" vs "redacted".
    /// A superset of the rows the redaction rule can change, filtered in SQL so the sweep reads a
    /// handful of rows instead of the whole table (a request walking every row of a large
    /// production `aud_logs` outlives the load balancer's idle timeout). Every key the rule
    /// matches — after dropping `_` and `-` — contains one of these words, and `SetPropertyCommand`
    /// rows are candidates for their declared mask; the exact rule is still applied per row.
    private static final Condition MAY_HOLD_A_SECRET = T.OPERATION.eq("SetPropertyCommand")
            .or(DSL.condition("{0}::text ~* {1}", T.OPERATION_JSON,
                    DSL.inline("password|secret|passphrase|token|api[_-]?key|private[_-]?key|authorization|cookie")));

    public RedactionResult redactExisting(BiFunction<String, JsonNode, JsonNode> redactor) {
        int scanned = 0;
        int redacted = 0;
        String afterId = null;
        while (true) {
            Condition where = afterId == null ? DSL.noCondition() : T.ID.gt(afterId);
            var batch = dsl.select(T.ID, T.OPERATION, T.OPERATION_JSON).from(T)
                    .where(where.and(MAY_HOLD_A_SECRET))
                    .orderBy(T.ID.asc())
                    .limit(REDACT_BATCH_SIZE)
                    .fetch();
            if (batch.isEmpty()) break;
            for (var row : batch) {
                scanned++;
                String id = row.get(T.ID);
                afterId = id;
                JsonNode original = fromJsonb(row.get(T.OPERATION_JSON));
                JsonNode next = redactor.apply(row.get(T.OPERATION), original);
                if (!Objects.equals(next, original)) {
                    dsl.update(T)
                            .set(T.OPERATION_JSON, next == null ? null : JSONB.jsonb(next.toString()))
                            .where(T.ID.eq(id))
                            .execute();
                    redacted++;
                }
            }
            if (batch.size() < REDACT_BATCH_SIZE) break;
        }
        return new RedactionResult(scanned, redacted);
    }

    /// `{scanned, redacted}` — [#redactExisting]'s result.
    public record RedactionResult(int scanned, int redacted) {
    }

    private static org.jooq.Insert<AudLogsRecord> insertQuery(DSLContext txDsl, AuditLog l) {
        return txDsl.insertInto(T)
                .set(T.ID, l.id())
                .set(T.ENTITY_TYPE, l.entityType())
                .set(T.ENTITY_ID, l.entityId())
                .set(T.OPERATION, l.operation())
                .set(T.OPERATION_JSON, l.operationJson() == null ? null : JSONB.jsonb(l.operationJson().toString()))
                .set(T.PRINCIPAL_ID, l.principalId())
                .set(T.APPLICATION_ID, l.applicationId())
                .set(T.CLIENT_ID, l.clientId())
                .set(T.PERFORMED_AT, l.performedAt().atOffset(ZoneOffset.UTC));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<AuditLog> findById(String id) {
        return logsWithPrincipal()
                .where(T.ID.eq(id))
                .fetchOptional()
                .map(AuditLogRepository::toEntity);
    }

    /// Up to `limit` entries matching every filter, newest first
    /// (`performed_at DESC, id DESC`), strictly after `after` when given.
    /// The caller over-fetches by one to learn whether a next page exists.
    public List<AuditLog> findWithCursor(CursorFilter f, KeysetCursor after, int limit) {
        Condition where = DSL.noCondition();
        if (f.entityType() != null) where = where.and(T.ENTITY_TYPE.eq(f.entityType()));
        if (f.entityId() != null) where = where.and(T.ENTITY_ID.eq(f.entityId()));
        if (f.principalId() != null) where = where.and(T.PRINCIPAL_ID.eq(f.principalId()));
        if (f.operation() != null) where = where.and(T.OPERATION.eq(f.operation()));
        if (!f.applicationIds().isEmpty()) where = where.and(T.APPLICATION_ID.in(f.applicationIds()));
        if (!f.clientIds().isEmpty()) where = where.and(T.CLIENT_ID.in(f.clientIds()));
        if (after != null) {
            where = where.and(DSL.row(T.PERFORMED_AT, T.ID).lt(after.at().atOffset(ZoneOffset.UTC), after.id()));
        }
        return logsWithPrincipal()
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
        return logsWithPrincipal()
                .where(where)
                .orderBy(T.PERFORMED_AT.desc(), T.ID.desc())
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

    /// Every entity read starts here: the row plus the principal's name,
    /// LEFT JOINed so rows without a (known) principal still read (spec §1).
    private SelectOnConditionStep<Record> logsWithPrincipal() {
        return dsl.select(T.fields()).select(P.NAME).from(T).leftJoin(P).on(P.ID.eq(T.PRINCIPAL_ID));
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
        } catch (JacksonException e) {
            throw new IllegalStateException("aud_logs.operation_json is not valid JSON", e);
        }
    }
}

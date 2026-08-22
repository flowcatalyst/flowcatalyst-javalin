package io.flowcatalyst.platform.process;

import io.flowcatalyst.db.generated.tables.MsgProcesses;
import io.flowcatalyst.db.generated.tables.records.MsgProcessesRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_PROCESSES;

/// `msg_processes` via jOOQ. Writes happen only on the unit of work's
/// transaction ([Persist]). Pure CRUD — no domain decisions live here. The
/// table has no `created_by` column, so that aggregate field is dropped on
/// write and `null` on read (spec §1).
public final class ProcessRepository implements Persist<Process> {

    private static final MsgProcesses T = MSG_PROCESSES;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ProcessRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Equality filters for [#findWithFilters]; `null` = no filter on that column.
    public record ListFilter(String application, String subdomain, String status) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Process> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    public Optional<Process> findByCode(String code) {
        return findOne(T.CODE.eq(code));
    }

    /// Every process whose first code segment is `applicationCode`, by code.
    public List<Process> findByApplication(String applicationCode) {
        return findMany(T.APPLICATION.eq(applicationCode));
    }

    /// Processes matching every non-null filter, by code.
    public List<Process> findWithFilters(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.application() != null) where = where.and(T.APPLICATION.eq(f.application()));
        if (f.subdomain() != null) where = where.and(T.SUBDOMAIN.eq(f.subdomain()));
        if (f.status() != null) where = where.and(T.STATUS.eq(f.status()));
        return findMany(where);
    }

    private Optional<Process> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(ProcessRepository::toEntity);
    }

    private List<Process> findMany(Condition where) {
        return dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch(ProcessRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here, not taken from
    /// the aggregate (spec §9). `tags` is always a (possibly empty) array —
    /// the column is `NOT NULL`.
    @Override
    public void persist(Process p, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CODE, p.code());
        row.put(T.NAME, p.name());
        row.put(T.DESCRIPTION, p.description());
        row.put(T.STATUS, p.status().name());
        row.put(T.SOURCE, p.source().name());
        row.put(T.APPLICATION, p.application());
        row.put(T.SUBDOMAIN, p.subdomain());
        row.put(T.PROCESS_NAME, p.processName());
        row.put(T.BODY, p.body());
        row.put(T.DIAGRAM_TYPE, p.diagramType());
        row.put(T.TAGS, p.tags().toArray(String[]::new));
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, p.id())
                .set(T.CREATED_AT, utc(p.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(Process p, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(p.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Process toEntity(MsgProcessesRecord row) {
        return new Process(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                ProcessStatus.parse(row.getStatus()),
                ProcessSource.parse(row.getSource()),
                row.getApplication(),
                row.getSubdomain(),
                row.getProcessName(),
                row.getBody(),
                row.getDiagramType(),
                row.getTags() == null ? List.of() : Arrays.asList(row.getTags()),
                null, // createdBy: not a column (spec §1)
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

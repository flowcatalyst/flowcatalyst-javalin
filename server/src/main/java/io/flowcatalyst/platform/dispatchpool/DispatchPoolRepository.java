package io.flowcatalyst.platform.dispatchpool;

import io.flowcatalyst.db.generated.tables.MsgDispatchPools;
import io.flowcatalyst.db.generated.tables.records.MsgDispatchPoolsRecord;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.MSG_DISPATCH_POOLS;

/// `msg_dispatch_pools` via jOOQ. Reads order by code; writes happen only on
/// the unit of work's transaction ([Persist]). Pure CRUD — no domain
/// decisions live here.
public final class DispatchPoolRepository implements Persist<DispatchPool> {

    private static final MsgDispatchPools T = MSG_DISPATCH_POOLS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public DispatchPoolRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Equality filters for [#findWithFilters]; `null` = no filter on that column.
    public record ListFilter(String status, String clientId) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<DispatchPool> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The pool with `code` in the given client scope; `clientId` `null`
    /// means the platform-wide pool (`client_id IS NULL`) — spec §6.
    public Optional<DispatchPool> findByCode(String code, String clientId) {
        return findOne(T.CODE.eq(code).and(clientId == null ? T.CLIENT_ID.isNull() : T.CLIENT_ID.eq(clientId)));
    }

    /// Every pool, by code — sync matches globally (spec §7).
    public List<DispatchPool> findAll() {
        return findMany(DSL.noCondition());
    }

    /// Pools matching every non-null filter, by code.
    public List<DispatchPool> findWithFilters(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.status() != null) where = where.and(T.STATUS.eq(f.status()));
        if (f.clientId() != null) where = where.and(T.CLIENT_ID.eq(f.clientId()));
        return findMany(where);
    }

    private Optional<DispatchPool> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(DispatchPoolRepository::toEntity);
    }

    private List<DispatchPool> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch().map(DispatchPoolRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts `ON CONFLICT (id)`. `created_at` is written once and never
    /// updated; `updated_at` is stamped `now()` here, not taken from the
    /// aggregate (spec §9).
    @Override
    public void persist(DispatchPool p, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CODE, p.code());
        row.put(T.NAME, p.name());
        row.put(T.DESCRIPTION, p.description());
        row.put(T.RATE_LIMIT, p.rateLimit());
        row.put(T.CONCURRENCY, p.concurrency());
        row.put(T.CLIENT_ID, p.clientId());
        row.put(T.CLIENT_IDENTIFIER, p.clientIdentifier());
        row.put(T.STATUS, p.status().name());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, p.id())
                .set(T.CREATED_AT, utc(p.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(DispatchPool p, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(p.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static DispatchPool toEntity(MsgDispatchPoolsRecord row) {
        return new DispatchPool(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                row.getRateLimit(),
                row.getConcurrency(),
                row.getClientId(),
                row.getClientIdentifier(),
                DispatchPoolStatus.parse(row.getStatus()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

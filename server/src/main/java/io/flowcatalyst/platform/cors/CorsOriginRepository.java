package io.flowcatalyst.platform.cors;

import io.flowcatalyst.db.generated.tables.TntCorsAllowedOrigins;
import io.flowcatalyst.db.generated.tables.records.TntCorsAllowedOriginsRecord;
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

import static io.flowcatalyst.db.generated.Tables.TNT_CORS_ALLOWED_ORIGINS;

/// `tnt_cors_allowed_origins` via jOOQ. One row in, one row out; writes
/// happen only on the unit of work's transaction ([Persist]). Pure CRUD — no
/// domain decisions live here.
///
/// [#allowedOrigins] is the read the CORS filter will build its cache on
/// (spec §9) and the one `GET /api/platform/cors/allowed` serves, so the two
/// can never disagree.
public final class CorsOriginRepository implements Persist<CorsOrigin> {

    private static final TntCorsAllowedOrigins T = TNT_CORS_ALLOWED_ORIGINS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public CorsOriginRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<CorsOrigin> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Exact match on the stored (trimmed) origin.
    public Optional<CorsOrigin> findByOrigin(String origin) {
        return findOne(T.ORIGIN.eq(origin));
    }

    /// Every allowlist entry, by origin.
    public List<CorsOrigin> findAll() {
        return findMany(DSL.noCondition());
    }

    /// The allowlist as origin strings, by origin — the filter's read (spec §9).
    public List<String> allowedOrigins() {
        return List.copyOf(dsl.select(T.ORIGIN).from(T).orderBy(T.ORIGIN.asc()).fetch(T.ORIGIN));
    }

    private Optional<CorsOrigin> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(CorsOriginRepository::toEntity);
    }

    private List<CorsOrigin> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.ORIGIN.asc()).fetch().map(CorsOriginRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_by` and `created_at` are
    /// written once and never updated; `updated_at` is stamped `now()` here,
    /// not taken from the aggregate (spec §8).
    @Override
    public void persist(CorsOrigin o, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.ORIGIN, o.origin());
        row.put(T.DESCRIPTION, o.description());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, o.id())
                .set(T.CREATED_BY, o.createdBy())
                .set(T.CREATED_AT, utc(o.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Removes the row.
    @Override
    public void delete(CorsOrigin o, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(o.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static CorsOrigin toEntity(TntCorsAllowedOriginsRecord row) {
        return new CorsOrigin(
                row.getId(),
                row.getOrigin(),
                row.getDescription(),
                row.getCreatedBy(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

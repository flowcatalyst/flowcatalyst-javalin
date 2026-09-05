package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.db.generated.tables.TntAnchorDomains;
import io.flowcatalyst.db.generated.tables.records.TntAnchorDomainsRecord;
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

import static io.flowcatalyst.db.generated.Tables.TNT_ANCHOR_DOMAINS;

/// `tnt_anchor_domains` via jOOQ. One row in, one row out; writes happen
/// only on the unit of work's transaction ([Persist]). Pure CRUD — no
/// domain decisions live here.
public final class AnchorDomainRepository implements Persist<AnchorDomain> {

    private static final TntAnchorDomains T = TNT_ANCHOR_DOMAINS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public AnchorDomainRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<AnchorDomain> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Exact match on the stored (normalised) domain.
    public Optional<AnchorDomain> findByDomain(String domain) {
        return findOne(T.DOMAIN.eq(domain));
    }

    /// Every anchor domain, by domain (spec §2).
    public List<AnchorDomain> findAll() {
        return findMany(DSL.noCondition());
    }

    private Optional<AnchorDomain> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(AnchorDomainRepository::toEntity);
    }

    private List<AnchorDomain> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.DOMAIN.asc()).fetch().map(AnchorDomainRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here, not taken from
    /// the aggregate (spec §2).
    @Override
    public void persist(AnchorDomain a, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.DOMAIN, a.domain());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, a.id())
                .set(T.CREATED_AT, utc(a.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Hard delete by id (spec §4.1).
    @Override
    public void delete(AnchorDomain a, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(a.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static AnchorDomain toEntity(TntAnchorDomainsRecord row) {
        return new AnchorDomain(row.getId(), row.getDomain(), row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.db.generated.tables.OauthIdpRoleMappings;
import io.flowcatalyst.db.generated.tables.records.OauthIdpRoleMappingsRecord;
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

import static io.flowcatalyst.db.generated.Tables.OAUTH_IDP_ROLE_MAPPINGS;

/// `oauth_idp_role_mappings` via jOOQ. One row in, one row out; writes
/// happen only on the unit of work's transaction ([Persist]). Pure CRUD —
/// no domain decisions live here. `idp_type` is nullable in the schema
/// (spec §6 D4); reads pass it through as stored, `null` included — the API
/// layer is where a legacy `null` becomes the wire's `""`.
public final class IdpRoleMappingRepository implements Persist<IdpRoleMapping> {

    private static final OauthIdpRoleMappings T = OAUTH_IDP_ROLE_MAPPINGS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public IdpRoleMappingRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<IdpRoleMapping> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Exact match on the stored role name (spec §4.3: uniqueness is on this alone).
    public Optional<IdpRoleMapping> findByIdpRoleName(String idpRoleName) {
        return findOne(T.IDP_ROLE_NAME.eq(idpRoleName));
    }

    /// Every mapping, by IdP role name (spec §2).
    public List<IdpRoleMapping> findAll() {
        return findMany(DSL.noCondition());
    }

    private Optional<IdpRoleMapping> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(IdpRoleMappingRepository::toEntity);
    }

    private List<IdpRoleMapping> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.IDP_ROLE_NAME.asc()).fetch().map(IdpRoleMappingRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here, not taken from
    /// the aggregate (spec §2). There is no update operation (spec §3) —
    /// this is only ever called once per row, at create.
    @Override
    public void persist(IdpRoleMapping m, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.IDP_ROLE_NAME, m.idpRoleName());
        row.put(T.INTERNAL_ROLE_NAME, m.platformRoleName());
        row.put(T.IDP_TYPE, m.idpType());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, m.id())
                .set(T.CREATED_AT, utc(m.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Hard delete by id (spec §4.3).
    @Override
    public void delete(IdpRoleMapping m, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(m.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static IdpRoleMapping toEntity(OauthIdpRoleMappingsRecord row) {
        return new IdpRoleMapping(row.getId(), row.getIdpType(), row.getIdpRoleName(), row.getInternalRoleName(),
                row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

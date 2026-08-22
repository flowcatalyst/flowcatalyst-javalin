package io.flowcatalyst.platform.role;

import io.flowcatalyst.db.generated.tables.IamPermissions;
import io.flowcatalyst.db.generated.tables.records.IamPermissionsRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
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

import static io.flowcatalyst.db.generated.Tables.IAM_PERMISSIONS;

/// The permission catalogue, `iam_permissions`, via jOOQ (spec §1, §9).
/// Catalogue rows live independently of roles (`iam_role_permissions` holds
/// plain strings and is the [RoleRepository]'s business). Writes take the
/// caller's transaction; the catalogue has no domain events, so they are
/// reached through `UnitOfWork.inTransaction` rather than an operation
/// (spec §3, open question 4).
public final class PermissionRepository {

    private static final IamPermissions T = IAM_PERMISSIONS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public PermissionRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    /// Every catalogue entry, by code.
    public List<Permission> findAll() {
        return List.copyOf(dsl.selectFrom(T).orderBy(T.CODE.asc()).fetch().map(PermissionRepository::toEntity));
    }

    public Optional<Permission> findByCode(String code) {
        return dsl.selectFrom(T).where(T.CODE.eq(code)).fetchOptional().map(PermissionRepository::toEntity);
    }

    // ── Writes (inside a transaction only) ─────────────────────────────────

    /// Upserts `ON CONFLICT (code)`: the segments, description and
    /// `updated_at` are refreshed; `id` and `created_at` are written once.
    public void upsert(Permission p, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.SUBDOMAIN, p.subdomain());
        row.put(T.CONTEXT, p.context());
        row.put(T.AGGREGATE, p.aggregate());
        row.put(T.ACTION, p.action());
        row.put(T.DESCRIPTION, p.description());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, p.id())
                .set(T.CODE, p.code())
                .set(T.CREATED_AT, utc(p.createdAt()))
                .set(row)
                .onConflict(T.CODE).doUpdate().set(row)
                .execute();
    }

    /// Removes the entry with `code`; no error when absent (idempotent).
    public void deleteByCode(String code, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.CODE.eq(code)).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Permission toEntity(IamPermissionsRecord row) {
        return new Permission(
                row.getId(),
                row.getCode(),
                row.getSubdomain(),
                row.getContext(),
                row.getAggregate(),
                row.getAction(),
                row.getDescription(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

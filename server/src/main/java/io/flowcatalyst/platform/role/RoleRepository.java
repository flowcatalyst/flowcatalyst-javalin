package io.flowcatalyst.platform.role;

import io.flowcatalyst.db.generated.tables.IamPrincipalRoles;
import io.flowcatalyst.db.generated.tables.IamRolePermissions;
import io.flowcatalyst.db.generated.tables.IamRoles;
import io.flowcatalyst.db.generated.tables.records.IamRolePermissionsRecord;
import io.flowcatalyst.db.generated.tables.records.IamRolesRecord;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLE_PERMISSIONS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `iam_roles` + `iam_role_permissions` via jOOQ. Reads hydrate the
/// permissions of every role in one extra query; writes happen only on the
/// unit of work's transaction ([Persist]) and replace the permission rows
/// wholesale (spec §9). Pure CRUD — no domain decisions live here.
public final class RoleRepository implements Persist<Role> {

    private static final IamRoles T = IAM_ROLES;
    private static final IamRolePermissions RP = IAM_ROLE_PERMISSIONS;
    private static final IamPrincipalRoles PR = IAM_PRINCIPAL_ROLES;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public RoleRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Role> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// By the unique canonical name (`{applicationCode}:{shortName}`).
    public Optional<Role> findByName(String name) {
        return findOne(T.NAME.eq(name));
    }

    /// The role whose canonical name is `{applicationCode}:{shortName}` in
    /// one of `applicationIds` — how SDK-synced principal assignments, which
    /// carry the bare short name, are resolved. Empty for a blank name or no
    /// applications.
    public Optional<Role> findByShortNameInApps(String shortName, List<String> applicationIds) {
        if (shortName == null || shortName.isEmpty() || applicationIds == null || applicationIds.isEmpty()) {
            return Optional.empty();
        }
        return dsl.selectFrom(T)
                .where(T.APPLICATION_ID.in(applicationIds)
                        .and(T.NAME.eq(DSL.concat(T.APPLICATION_CODE, DSL.inline(":" + shortName)))))
                .limit(1)
                .fetchOptional()
                .map(row -> toEntity(row, permissionsFor(List.of(row.getId())).getOrDefault(row.getId(), List.of())));
    }

    /// Every role, by name.
    public List<Role> findAll() {
        return findMany(DSL.noCondition());
    }

    /// Every role with the given source, by name.
    public List<Role> findBySource(RoleSource source) {
        return findMany(T.SOURCE.eq(source.name()));
    }

    /// Every role stamped with `applicationId`, by name.
    public List<Role> findByApplicationId(String applicationId) {
        return findMany(T.APPLICATION_ID.eq(applicationId));
    }

    /// How many principals currently hold the role named `name`
    /// (`iam_principal_roles` references roles by name, without a foreign key).
    public long countAssignments(String name) {
        return dsl.fetchCount(PR, PR.ROLE_NAME.eq(name));
    }

    /// The distinct non-null `application_code` values across all roles, ordered.
    public List<String> applicationCodes() {
        return dsl.selectDistinct(T.APPLICATION_CODE).from(T)
                .where(T.APPLICATION_CODE.isNotNull())
                .orderBy(T.APPLICATION_CODE.asc())
                .fetch(T.APPLICATION_CODE);
    }

    private Optional<Role> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional()
                .map(row -> toEntity(row, permissionsFor(List.of(row.getId())).getOrDefault(row.getId(), List.of())));
    }

    private List<Role> findMany(Condition where) {
        var rows = dsl.selectFrom(T).where(where).orderBy(T.NAME.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var permissionsById = permissionsFor(rows.getValues(T.ID));
        return List.copyOf(rows.map(row -> toEntity(row, permissionsById.getOrDefault(row.getId(), List.of()))));
    }

    /// Permission codes for many roles in one query.
    private Map<String, List<String>> permissionsFor(List<String> roleIds) {
        return dsl.selectFrom(RP)
                .where(RP.ROLE_ID.in(roleIds))
                .fetch().stream()
                .collect(groupingBy(IamRolePermissionsRecord::getRoleId,
                        mapping(IamRolePermissionsRecord::getPermission, toList())));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)` (`created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here, not taken from
    /// the aggregate — spec §9) and replaces the permission rows wholesale.
    @Override
    public void persist(Role role, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.APPLICATION_ID, role.applicationId());
        row.put(T.NAME, role.name());
        row.put(T.DISPLAY_NAME, role.displayName());
        row.put(T.DESCRIPTION, role.description());
        row.put(T.APPLICATION_CODE, role.applicationCode());
        row.put(T.SOURCE, role.source().name());
        row.put(T.CLIENT_MANAGED, role.clientManaged());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, role.id())
                .set(T.CREATED_AT, utc(role.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();

        txDsl.deleteFrom(RP).where(RP.ROLE_ID.eq(role.id())).execute();
        for (String permission : role.permissions()) {
            txDsl.insertInto(RP)
                    .set(RP.ROLE_ID, role.id())
                    .set(RP.PERMISSION, permission)
                    .execute();
        }
    }

    /// Removes the permission rows, then the role.
    @Override
    public void delete(Role role, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(RP).where(RP.ROLE_ID.eq(role.id())).execute();
        txDsl.deleteFrom(T).where(T.ID.eq(role.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Role toEntity(IamRolesRecord row, List<String> permissions) {
        return new Role(
                row.getId(),
                row.getApplicationId(),
                row.getName(),
                row.getDisplayName(),
                row.getDescription(),
                row.getApplicationCode(),
                permissions,
                RoleSource.parse(row.getSource()),
                row.getClientManaged(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

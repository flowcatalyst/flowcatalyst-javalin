package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.db.generated.tables.AppPlatformConfigAccess;
import io.flowcatalyst.db.generated.tables.records.AppPlatformConfigAccessRecord;
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

import static io.flowcatalyst.db.generated.Tables.APP_PLATFORM_CONFIG_ACCESS;

/// `app_platform_config_access` via jOOQ — the [ConfigAccess] store. Reads
/// go through the pool; writes only on the unit of work's transaction
/// ([Persist]). The two `can*` queries are the data behind the read / write
/// access rules (spec §3); the rule itself lives in `operations/Access`.
public final class ConfigAccessRepository implements Persist<ConfigAccess> {

    private static final AppPlatformConfigAccess T = APP_PLATFORM_CONFIG_ACCESS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ConfigAccessRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<ConfigAccess> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The grant for one `(application, role)` pair — unique by constraint.
    public Optional<ConfigAccess> findByRole(String applicationCode, String roleCode) {
        return findOne(T.APPLICATION_CODE.eq(applicationCode).and(T.ROLE_CODE.eq(roleCode)));
    }

    /// Every grant of one application, by role code.
    public List<ConfigAccess> findByApplication(String applicationCode) {
        return List.copyOf(dsl.selectFrom(T).where(T.APPLICATION_CODE.eq(applicationCode)).orderBy(T.ROLE_CODE.asc())
                .fetch().map(ConfigAccessRepository::toEntity));
    }

    /// Whether any of `roleCodes` holds a read grant on `applicationCode`
    /// (an empty role set never does).
    public boolean canRead(String applicationCode, List<String> roleCodes) {
        return anyGrant(applicationCode, roleCodes, T.CAN_READ.isTrue());
    }

    /// Whether any of `roleCodes` holds a write grant on `applicationCode`.
    public boolean canWrite(String applicationCode, List<String> roleCodes) {
        return anyGrant(applicationCode, roleCodes, T.CAN_WRITE.isTrue());
    }

    private boolean anyGrant(String applicationCode, List<String> roleCodes, Condition level) {
        if (roleCodes.isEmpty()) return false;
        return dsl.fetchExists(dsl.selectOne().from(T)
                .where(T.APPLICATION_CODE.eq(applicationCode)).and(T.ROLE_CODE.in(roleCodes)).and(level));
    }

    private Optional<ConfigAccess> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(ConfigAccessRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts `ON CONFLICT (id)`: only `can_read` / `can_write` change on an
    /// existing row; the pair and `created_at` are insert-only (spec §9).
    @Override
    public void persist(ConfigAccess a, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CAN_READ, a.canRead());
        row.put(T.CAN_WRITE, a.canWrite());
        txDsl.insertInto(T)
                .set(T.ID, a.id())
                .set(T.APPLICATION_CODE, a.applicationCode())
                .set(T.ROLE_CODE, a.roleCode())
                .set(T.CREATED_AT, utc(a.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(ConfigAccess a, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(a.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ConfigAccess toEntity(AppPlatformConfigAccessRecord row) {
        return new ConfigAccess(
                row.getId(),
                row.getApplicationCode(),
                row.getRoleCode(),
                row.getCanRead(),
                row.getCanWrite(),
                row.getCreatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

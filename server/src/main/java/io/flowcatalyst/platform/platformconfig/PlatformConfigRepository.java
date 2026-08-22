package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.db.generated.tables.AppPlatformConfigs;
import io.flowcatalyst.db.generated.tables.records.AppPlatformConfigsRecord;
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

import static io.flowcatalyst.db.generated.Tables.APP_PLATFORM_CONFIGS;

/// `app_platform_configs` via jOOQ — the [PlatformConfig] store. Reads go
/// through the pool; writes only on the unit of work's transaction
/// ([Persist]). Pure CRUD — no domain decisions live here.
///
/// [#findByCoordinate] is also the read the pre-login public endpoints and
/// branding need (spec §10): `findByCoordinate(ConfigCoordinate.global("platform", "login", "theme"))`.
public final class PlatformConfigRepository implements Persist<PlatformConfig> {

    private static final AppPlatformConfigs T = APP_PLATFORM_CONFIGS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public PlatformConfigRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<PlatformConfig> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The value at `coordinate`: scope derived from the coordinate, a
    /// `GLOBAL` lookup matching `client_id IS NULL` (spec §1.1).
    public Optional<PlatformConfig> findByCoordinate(ConfigCoordinate c) {
        Condition client = c.clientId() == null ? T.CLIENT_ID.isNull() : T.CLIENT_ID.eq(c.clientId());
        return findOne(T.APPLICATION_CODE.eq(c.applicationCode())
                .and(T.SECTION.eq(c.section()))
                .and(T.PROPERTY.eq(c.property()))
                .and(T.SCOPE.eq(c.scope().name()))
                .and(client));
    }

    /// Every value of one application, by `section, property`.
    public List<PlatformConfig> findByApplication(String applicationCode) {
        return findMany(T.APPLICATION_CODE.eq(applicationCode));
    }

    private Optional<PlatformConfig> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(PlatformConfigRepository::toEntity);
    }

    private List<PlatformConfig> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.SECTION.asc(), T.PROPERTY.asc()).fetch()
                .map(PlatformConfigRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts `ON CONFLICT (id)`: only `value_type`, `value`, `description`
    /// and `updated_at` change on an existing row; the coordinate and
    /// `created_at` are insert-only; `updated_at` is stamped `now()` here (spec §9).
    @Override
    public void persist(PlatformConfig c, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.VALUE_TYPE, c.valueType().name());
        row.put(T.VALUE, c.value());
        row.put(T.DESCRIPTION, c.description());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, c.id())
                .set(T.APPLICATION_CODE, c.applicationCode())
                .set(T.SECTION, c.section())
                .set(T.PROPERTY, c.property())
                .set(T.SCOPE, c.scope().name())
                .set(T.CLIENT_ID, c.clientId())
                .set(T.CREATED_AT, utc(c.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(PlatformConfig c, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(c.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static PlatformConfig toEntity(AppPlatformConfigsRecord row) {
        return new PlatformConfig(
                row.getId(),
                row.getApplicationCode(),
                row.getSection(),
                row.getProperty(),
                ConfigScope.parse(row.getScope()),
                row.getClientId(),
                ConfigValueType.parse(row.getValueType()),
                row.getValue(),
                row.getDescription(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

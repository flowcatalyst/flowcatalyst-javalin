package io.flowcatalyst.platform.application;

import io.flowcatalyst.db.generated.tables.AppApplications;
import io.flowcatalyst.db.generated.tables.records.AppApplicationsRecord;
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

import static io.flowcatalyst.db.generated.Tables.APP_APPLICATIONS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;

/// `app_applications` via jOOQ. Reads go through the pool; writes happen only
/// on the unit of work's transaction ([Persist]). Pure CRUD — no domain
/// decisions live here.
///
/// [#findById] and [#findByCode] are the stable read-only lookup other
/// aggregates (client, sdksync, principal) depend on.
public final class ApplicationRepository implements Persist<Application> {

    private static final AppApplications T = APP_APPLICATIONS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ApplicationRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Equality filters for [#findWithFilters]; `null` = no filter on that column.
    public record ListFilter(ApplicationType type, Boolean active) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Application> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// By normalised code (the stored form).
    public Optional<Application> findByCode(String code) {
        return findOne(T.CODE.eq(code));
    }

    /// Applications matching every non-null filter, by code.
    public List<Application> findWithFilters(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.type() != null) where = where.and(T.TYPE.eq(f.type().name()));
        if (f.active() != null) where = where.and(T.ACTIVE.eq(f.active()));
        return findMany(where);
    }

    private Optional<Application> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(ApplicationRepository::toEntity);
    }

    private List<Application> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch().map(ApplicationRepository::toEntity));
    }

    // ── Cross-aggregate lookups the operations need (spec §9) ──────────────
    // Read-only, by id, into tables owned by the client and principal
    // aggregates; kept here so this package has no compile-time dependency on
    // units that land concurrently. Replace with those repositories' reads
    // once they exist.

    /// Whether a client row with `clientId` exists.
    public boolean clientExists(String clientId) {
        return dsl.fetchExists(dsl.selectOne().from(TNT_CLIENTS).where(TNT_CLIENTS.ID.eq(clientId)));
    }

    /// The id of the principal linked to service account `serviceAccountId`
    /// — what `app_applications.service_account_id` references.
    public Optional<String> servicePrincipalIdFor(String serviceAccountId) {
        return dsl.select(IAM_PRINCIPALS.ID).from(IAM_PRINCIPALS)
                .where(IAM_PRINCIPALS.SERVICE_ACCOUNT_ID.eq(serviceAccountId))
                .limit(1)
                .fetchOptional(IAM_PRINCIPALS.ID);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here (spec §9).
    @Override
    public void persist(Application a, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.TYPE, a.type().name());
        row.put(T.CODE, a.code());
        row.put(T.NAME, a.name());
        row.put(T.DESCRIPTION, a.description());
        row.put(T.ICON_URL, a.iconUrl());
        row.put(T.WEBSITE, a.website());
        row.put(T.LOGO, a.logo());
        row.put(T.LOGO_MIME_TYPE, a.logoMimeType());
        row.put(T.DEFAULT_BASE_URL, a.defaultBaseUrl());
        row.put(T.SERVICE_ACCOUNT_ID, a.serviceAccountId());
        row.put(T.ACTIVE, a.active());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, a.id())
                .set(T.CREATED_AT, utc(a.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Removes the application row only (spec §3, open question 6).
    @Override
    public void delete(Application a, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(a.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Application toEntity(AppApplicationsRecord row) {
        return new Application(
                row.getId(),
                ApplicationType.parse(row.getType()),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                row.getIconUrl(),
                row.getWebsite(),
                row.getLogo(),
                row.getLogoMimeType(),
                row.getDefaultBaseUrl(),
                row.getServiceAccountId(),
                row.getActive(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /// `id → code` for the given ids, for the `applications` claim's
    /// `"{id}:{code}"` pairs ([io.flowcatalyst.platform.auth.token.ClaimLabels]).
    /// Unknown ids are simply absent.
    public java.util.Map<String, String> codesByIds(java.util.Collection<String> ids) {
        if (ids.isEmpty()) return java.util.Map.of();
        return dsl.select(T.ID, T.CODE).from(T).where(T.ID.in(ids))
                .fetchMap(T.ID, T.CODE);
    }
}

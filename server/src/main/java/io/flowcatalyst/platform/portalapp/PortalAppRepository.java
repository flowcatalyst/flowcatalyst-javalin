package io.flowcatalyst.platform.portalapp;

import io.flowcatalyst.db.generated.tables.PortalApps;
import io.flowcatalyst.db.generated.tables.records.PortalAppsRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENTS;
import static io.flowcatalyst.db.generated.Tables.PORTAL_APPS;
import static io.flowcatalyst.db.generated.Tables.PORTAL_IDENTITY_APPS;

/// `portal_apps` via jOOQ (spec `portal-apps.md` §1, §2.4, the shape of
/// `PortalIdentityRepository`). Pure CRUD — no business decisions live here.
public final class PortalAppRepository implements Persist<PortalApp> {

    private static final PortalApps T = PORTAL_APPS;

    private final DSLContext dsl;

    public PortalAppRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<PortalApp> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Normalises `code` before comparing (spec §2.1: codes are
    /// case-insensitive on input; the stored form is already normalised).
    public Optional<PortalApp> findByClientAndCode(String clientId, String code) {
        return findOne(T.CLIENT_ID.eq(clientId).and(T.CODE.eq(PortalAppCode.normalize(code))));
    }

    /// "The app for OAuth client X" (spec §2.4): `X` is the OAuth `client_id`
    /// string, not `oauth_clients.id`. Empty for an unlinked/legacy client —
    /// a portal client without `portal_app_id`, or no such OAuth client.
    public Optional<PortalApp> findByOAuthClientId(String oauthClientId) {
        return findOne(T.ID.eq(dsl.select(OAUTH_CLIENTS.PORTAL_APP_ID).from(OAUTH_CLIENTS)
                .where(OAUTH_CLIENTS.CLIENT_ID.eq(oauthClientId))));
    }

    /// Every portal app of one client, ordered by name (spec §4.4).
    public List<PortalApp> findByClient(String clientId) {
        return dsl.selectFrom(T).where(T.CLIENT_ID.eq(clientId)).orderBy(T.NAME.asc())
                .fetch().map(PortalAppRepository::toEntity);
    }

    /// Every portal app, ordered by name (spec §4.4: anchors with no
    /// `clientId` filter get every client's apps).
    public List<PortalApp> findAll() {
        return dsl.selectFrom(T).orderBy(T.NAME.asc()).fetch().map(PortalAppRepository::toEntity);
    }

    /// Every app named by `ids`, one query — the batch resolution `listPortalUsers`
    /// (spec §4.2) needs for a page's grants: never one lookup per row. An id
    /// with no matching row is simply absent from the map.
    public Map<String, PortalApp> findByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, PortalApp> byId = new HashMap<>();
        dsl.selectFrom(T).where(T.ID.in(ids)).fetch()
                .forEach(row -> byId.put(row.getId(), toEntity(row)));
        return byId;
    }

    /// Grant-row count per app id, one query (spec §4.4 `userCount`); an id
    /// with no grants is absent from the map (callers default to 0).
    public Map<String, Integer> userCounts(Collection<String> appIds) {
        Map<String, Integer> counts = new HashMap<>();
        if (appIds.isEmpty()) {
            return counts;
        }
        dsl.select(PORTAL_IDENTITY_APPS.PORTAL_APP_ID, org.jooq.impl.DSL.count())
                .from(PORTAL_IDENTITY_APPS)
                .where(PORTAL_IDENTITY_APPS.PORTAL_APP_ID.in(appIds))
                .groupBy(PORTAL_IDENTITY_APPS.PORTAL_APP_ID)
                .forEach(r -> counts.put(r.value1(), r.value2()));
        return counts;
    }

    private Optional<PortalApp> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(PortalAppRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upsert by id. `code` and `client_id` are absent from the `SET` list —
    /// both are immutable after creation (spec §2.1, §3.5).
    @Override
    public void persist(PortalApp a, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.insertInto(T)
                .set(T.ID, a.id())
                .set(T.CLIENT_ID, a.clientId())
                .set(T.CODE, a.code())
                .set(T.NAME, a.name())
                .set(T.DESCRIPTION, a.description())
                .set(T.ACTIVE, a.active())
                .set(T.CREATED_AT, utc(a.createdAt()))
                .set(T.UPDATED_AT, utc(a.updatedAt()))
                .onConflict(T.ID).doUpdate()
                .set(T.NAME, a.name())
                .set(T.DESCRIPTION, a.description())
                .set(T.ACTIVE, a.active())
                .set(T.UPDATED_AT, utc(a.updatedAt()))
                .execute();
    }

    /// The `portal_identity_apps` grants cascade via FK (spec §10); no
    /// explicit junction cleanup needed here.
    @Override
    public void delete(PortalApp a, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(a.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static PortalApp toEntity(PortalAppsRecord row) {
        return new PortalApp(
                row.getId(),
                row.getClientId(),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                Boolean.TRUE.equals(row.getActive()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

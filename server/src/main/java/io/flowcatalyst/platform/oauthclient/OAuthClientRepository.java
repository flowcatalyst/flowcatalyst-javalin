package io.flowcatalyst.platform.oauthclient;

import io.flowcatalyst.db.generated.tables.records.OauthClientsRecord;
import io.flowcatalyst.platform.application.ApplicationRepository;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENTS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_ALLOWED_ORIGINS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_APPLICATION_IDS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_GRANT_TYPES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENT_REDIRECT_URIS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `oauth_clients` + its five junctions (redirect URIs, post-logout redirect
/// URIs, grant types, allowed origins, application ids) via jOOQ (spec
/// `auth-core.md` §3.6). Reads hydrate every junction in one extra query
/// each; writes clear-and-reinsert each junction inside the unit of work's
/// transaction. Pure CRUD — no business decisions live here.
public final class OAuthClientRepository implements Persist<OAuthClient> {

    private static final io.flowcatalyst.db.generated.tables.OauthClients T = OAUTH_CLIENTS;

    private final DSLContext dsl;
    private final ApplicationRepository applications;

    public OAuthClientRepository(DataSource dataSource, ApplicationRepository applications) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.applications = Objects.requireNonNull(applications, "applications");
    }

    /// One `{id, name}` display pair per application id, resolved via the
    /// application repository; a deleted (or never-existing) application
    /// renders `name = id` — the SPA reads `applications.length`
    /// unconditionally, so a placeholder chip beats an absent one.
    public record ApplicationRef(String id, String name) {
    }

    /// Resolves `applicationIds` to `{id, name}` pairs, in order.
    public List<ApplicationRef> applicationRefs(List<String> applicationIds) {
        return applicationIds.stream()
                .map(id -> new ApplicationRef(id, applications.findById(id).map(a -> a.name()).orElse(id)))
                .toList();
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<OAuthClient> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Whether an **active** client linked to `applicationId` allows the
    /// `authorization_code` grant — the application aggregate's
    /// `hasLoginClient` (spec `application.md` §3, §11 q3, now implemented:
    /// the SPA gates its "provision login client" form on it).
    public boolean hasLoginClientFor(String applicationId) {
        return dsl.fetchExists(dsl.selectOne().from(T)
                .join(OAUTH_CLIENT_APPLICATION_IDS).on(OAUTH_CLIENT_APPLICATION_IDS.OAUTH_CLIENT_ID.eq(T.ID))
                .join(OAUTH_CLIENT_GRANT_TYPES).on(OAUTH_CLIENT_GRANT_TYPES.OAUTH_CLIENT_ID.eq(T.ID))
                .where(OAUTH_CLIENT_APPLICATION_IDS.APPLICATION_ID.eq(applicationId))
                .and(OAUTH_CLIENT_GRANT_TYPES.GRANT_TYPE.eq("authorization_code"))
                .and(T.ACTIVE.isTrue()));
    }

    public Optional<OAuthClient> findByClientId(String clientId) {
        return findOne(T.CLIENT_ID.eq(clientId));
    }

    /// OAuth clients linked to a `SERVICE` principal
    /// (`service_account_principal_id = principalId`), earliest by
    /// `(created_at, id)` first — consulted by the service-account read to
    /// surface the public `client_id` of the account's provisioned OAuth
    /// client (`docs/spec/login-attempt-links.md` B1). Same hydration shape
    /// as [#findAll]/[#findByPortalAppId]; the condition and ordering are the
    /// only things that vary.
    public List<OAuthClient> findByPrincipalId(String principalId) {
        var rows = dsl.selectFrom(T).where(T.SERVICE_ACCOUNT_PRINCIPAL_ID.eq(principalId))
                .orderBy(T.CREATED_AT.asc(), T.ID.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var ids = rows.getValues(T.ID);
        var redirectUris = redirectUrisFor(ids);
        var postLogoutUris = postLogoutRedirectUrisFor(ids);
        var grantTypes = grantTypesFor(ids);
        var allowedOrigins = allowedOriginsFor(ids);
        var applicationIds = applicationIdsFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row,
                redirectUris.getOrDefault(row.getId(), List.of()),
                postLogoutUris.getOrDefault(row.getId(), List.of()),
                grantTypes.getOrDefault(row.getId(), List.of()),
                allowedOrigins.getOrDefault(row.getId(), List.of()),
                applicationIds.getOrDefault(row.getId(), List.of()))));
    }

    /// Every OAuth client linked to `portalAppId` (`portal_app_id = id`),
    /// ordered by name — the delete orchestration's read (spec `portal-apps.md`
    /// §3.6): a full aggregate per row (not the [LinkedRef] projection below)
    /// because each one is individually deleted and emits its own
    /// `OAuthClientDeleted`. Same hydration shape as [#findAll], the
    /// condition is the only thing that varies.
    public List<OAuthClient> findByPortalAppId(String portalAppId) {
        var rows = dsl.selectFrom(T).where(T.PORTAL_APP_ID.eq(portalAppId)).orderBy(T.CLIENT_NAME.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var ids = rows.getValues(T.ID);
        var redirectUris = redirectUrisFor(ids);
        var postLogoutUris = postLogoutRedirectUrisFor(ids);
        var grantTypes = grantTypesFor(ids);
        var allowedOrigins = allowedOriginsFor(ids);
        var applicationIds = applicationIdsFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row,
                redirectUris.getOrDefault(row.getId(), List.of()),
                postLogoutUris.getOrDefault(row.getId(), List.of()),
                grantTypes.getOrDefault(row.getId(), List.of()),
                allowedOrigins.getOrDefault(row.getId(), List.of()),
                applicationIds.getOrDefault(row.getId(), List.of()))));
    }

    /// The `{id, clientId, clientName}` a portal app's page needs per linked
    /// OAuth client (spec §4.4) — a light projection, not the full aggregate,
    /// batched over every app on the page in one query (no per-app reads).
    /// Ordered by name within each `portalAppId` group (the caller groups the
    /// flat list; the underlying `ORDER BY` makes every group's slice ordered
    /// too).
    public record LinkedRef(String portalAppId, String id, String clientId, String clientName) {
    }

    public List<LinkedRef> linkedTo(Collection<String> portalAppIds) {
        if (portalAppIds.isEmpty()) {
            return List.of();
        }
        return dsl.select(T.PORTAL_APP_ID, T.ID, T.CLIENT_ID, T.CLIENT_NAME)
                .from(T)
                .where(T.PORTAL_APP_ID.in(portalAppIds))
                .orderBy(T.CLIENT_NAME.asc())
                .fetch()
                .map(r -> new LinkedRef(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    public List<OAuthClient> findAll() {
        var rows = dsl.selectFrom(T).orderBy(T.CLIENT_NAME.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var ids = rows.getValues(T.ID);
        var redirectUris = redirectUrisFor(ids);
        var postLogoutUris = postLogoutRedirectUrisFor(ids);
        var grantTypes = grantTypesFor(ids);
        var allowedOrigins = allowedOriginsFor(ids);
        var applicationIds = applicationIdsFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row,
                redirectUris.getOrDefault(row.getId(), List.of()),
                postLogoutUris.getOrDefault(row.getId(), List.of()),
                grantTypes.getOrDefault(row.getId(), List.of()),
                allowedOrigins.getOrDefault(row.getId(), List.of()),
                applicationIds.getOrDefault(row.getId(), List.of()))));
    }

    /// One hydration path: every single-row lookup goes through this, the
    /// condition is the only thing that varies (CONVENTIONS §8, promoted
    /// from the `principal` audit).
    private Optional<OAuthClient> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(row -> {
            List<String> ids = List.of(row.getId());
            return toEntity(row,
                    redirectUrisFor(ids).getOrDefault(row.getId(), List.of()),
                    postLogoutRedirectUrisFor(ids).getOrDefault(row.getId(), List.of()),
                    grantTypesFor(ids).getOrDefault(row.getId(), List.of()),
                    allowedOriginsFor(ids).getOrDefault(row.getId(), List.of()),
                    applicationIdsFor(ids).getOrDefault(row.getId(), List.of()));
        });
    }

    private Map<String, List<String>> redirectUrisFor(List<String> ids) {
        return dsl.selectFrom(OAUTH_CLIENT_REDIRECT_URIS).where(OAUTH_CLIENT_REDIRECT_URIS.OAUTH_CLIENT_ID.in(ids))
                .fetch().stream().collect(groupingBy(r -> r.getOauthClientId(),
                        mapping(r -> r.getRedirectUri(), toList())));
    }

    private Map<String, List<String>> postLogoutRedirectUrisFor(List<String> ids) {
        return dsl.selectFrom(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS).where(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS.OAUTH_CLIENT_ID.in(ids))
                .fetch().stream().collect(groupingBy(r -> r.getOauthClientId(),
                        mapping(r -> r.getPostLogoutRedirectUri(), toList())));
    }

    private Map<String, List<String>> grantTypesFor(List<String> ids) {
        return dsl.selectFrom(OAUTH_CLIENT_GRANT_TYPES).where(OAUTH_CLIENT_GRANT_TYPES.OAUTH_CLIENT_ID.in(ids))
                .fetch().stream().collect(groupingBy(r -> r.getOauthClientId(),
                        mapping(r -> r.getGrantType(), toList())));
    }

    private Map<String, List<String>> allowedOriginsFor(List<String> ids) {
        return dsl.selectFrom(OAUTH_CLIENT_ALLOWED_ORIGINS).where(OAUTH_CLIENT_ALLOWED_ORIGINS.OAUTH_CLIENT_ID.in(ids))
                .fetch().stream().collect(groupingBy(r -> r.getOauthClientId(),
                        mapping(r -> r.getAllowedOrigin(), toList())));
    }

    private Map<String, List<String>> applicationIdsFor(List<String> ids) {
        return dsl.selectFrom(OAUTH_CLIENT_APPLICATION_IDS).where(OAUTH_CLIENT_APPLICATION_IDS.OAUTH_CLIENT_ID.in(ids))
                .fetch().stream().collect(groupingBy(r -> r.getOauthClientId(),
                        mapping(r -> r.getApplicationId(), toList())));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row, then clear-and-reinserts every junction — the same
    /// shape as `eventtype`'s spec-version write, repeated five times.
    @Override
    public void persist(OAuthClient c, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        OffsetDateTime now = utc(Instant.now());

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CLIENT_ID, c.clientId());
        row.put(T.CLIENT_NAME, c.clientName());
        row.put(T.CLIENT_TYPE, c.clientType().name());
        row.put(T.CLIENT_SECRET_REF, c.secretRef());
        row.put(T.PREVIOUS_SECRET_REF, c.previousSecretRef());
        row.put(T.PREVIOUS_SECRET_EXPIRES_AT, utcOrNull(c.previousSecretExpiresAt()));
        row.put(T.PREVIOUS_SECRET_LAST_USED_AT, utcOrNull(c.previousSecretLastUsedAt()));
        row.put(T.DEFAULT_SCOPES, joinScopes(c.defaultScopes()));
        row.put(T.PKCE_REQUIRED, c.pkceRequired());
        row.put(T.SERVICE_ACCOUNT_PRINCIPAL_ID, c.principalId());
        row.put(T.ACTIVE, c.active());
        row.put(T.PORTAL_CLIENT_ID, c.portalClientId());
        row.put(T.PORTAL_APP_ID, c.portalAppId());
        row.put(T.API_ACCESS, c.apiAccess());
        row.put(T.UPDATED_AT, now);
        txDsl.insertInto(T)
                .set(T.ID, c.id())
                .set(T.CREATED_AT, utc(c.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();

        txDsl.deleteFrom(OAUTH_CLIENT_REDIRECT_URIS).where(OAUTH_CLIENT_REDIRECT_URIS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        for (String uri : c.redirectUris()) {
            txDsl.insertInto(OAUTH_CLIENT_REDIRECT_URIS)
                    .set(OAUTH_CLIENT_REDIRECT_URIS.OAUTH_CLIENT_ID, c.id())
                    .set(OAUTH_CLIENT_REDIRECT_URIS.REDIRECT_URI, uri)
                    .onConflictDoNothing().execute();
        }

        txDsl.deleteFrom(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS).where(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        for (String uri : c.postLogoutRedirectUris()) {
            txDsl.insertInto(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS)
                    .set(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS.OAUTH_CLIENT_ID, c.id())
                    .set(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS.POST_LOGOUT_REDIRECT_URI, uri)
                    .onConflictDoNothing().execute();
        }

        txDsl.deleteFrom(OAUTH_CLIENT_GRANT_TYPES).where(OAUTH_CLIENT_GRANT_TYPES.OAUTH_CLIENT_ID.eq(c.id())).execute();
        for (String grant : c.grantTypes()) {
            txDsl.insertInto(OAUTH_CLIENT_GRANT_TYPES)
                    .set(OAUTH_CLIENT_GRANT_TYPES.OAUTH_CLIENT_ID, c.id())
                    .set(OAUTH_CLIENT_GRANT_TYPES.GRANT_TYPE, grant)
                    .onConflictDoNothing().execute();
        }

        txDsl.deleteFrom(OAUTH_CLIENT_ALLOWED_ORIGINS).where(OAUTH_CLIENT_ALLOWED_ORIGINS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        for (String origin : c.allowedOrigins()) {
            txDsl.insertInto(OAUTH_CLIENT_ALLOWED_ORIGINS)
                    .set(OAUTH_CLIENT_ALLOWED_ORIGINS.OAUTH_CLIENT_ID, c.id())
                    .set(OAUTH_CLIENT_ALLOWED_ORIGINS.ALLOWED_ORIGIN, origin)
                    .onConflictDoNothing().execute();
        }

        txDsl.deleteFrom(OAUTH_CLIENT_APPLICATION_IDS).where(OAUTH_CLIENT_APPLICATION_IDS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        for (String appId : c.applicationIds()) {
            txDsl.insertInto(OAUTH_CLIENT_APPLICATION_IDS)
                    .set(OAUTH_CLIENT_APPLICATION_IDS.OAUTH_CLIENT_ID, c.id())
                    .set(OAUTH_CLIENT_APPLICATION_IDS.APPLICATION_ID, appId)
                    .onConflictDoNothing().execute();
        }
    }

    /// Clears every junction explicitly (FK `ON DELETE CASCADE` would do it
    /// too, but explicit keeps the transaction self-describing), then the row.
    @Override
    public void delete(OAuthClient c, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(OAUTH_CLIENT_REDIRECT_URIS).where(OAUTH_CLIENT_REDIRECT_URIS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        txDsl.deleteFrom(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS).where(OAUTH_CLIENT_POST_LOGOUT_REDIRECT_URIS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        txDsl.deleteFrom(OAUTH_CLIENT_GRANT_TYPES).where(OAUTH_CLIENT_GRANT_TYPES.OAUTH_CLIENT_ID.eq(c.id())).execute();
        txDsl.deleteFrom(OAUTH_CLIENT_ALLOWED_ORIGINS).where(OAUTH_CLIENT_ALLOWED_ORIGINS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        txDsl.deleteFrom(OAUTH_CLIENT_APPLICATION_IDS).where(OAUTH_CLIENT_APPLICATION_IDS.OAUTH_CLIENT_ID.eq(c.id())).execute();
        txDsl.deleteFrom(T).where(T.ID.eq(c.id())).execute();
    }

    /// The rotation signal (`docs/spec/auth-core.md` §5, ruling A-22): a
    /// client that authenticated with its superseded secret is stamped so
    /// an operator can see who has not redeployed. Coalesced: the write is
    /// skipped while the stored stamp is later than `unlessAfter`, so a
    /// busy client does not turn every token request into an UPDATE.
    /// Runs in its own autocommit statement — the auth flow's outcome must
    /// not depend on it.
    ///
    /// @return whether a row was stamped
    public boolean touchPreviousSecretUsed(String id, Instant at, Instant unlessAfter) {
        return dsl.update(T)
                .set(T.PREVIOUS_SECRET_LAST_USED_AT, utcOrNull(at))
                .where(T.ID.eq(id))
                .and(T.PREVIOUS_SECRET_LAST_USED_AT.isNull().or(T.PREVIOUS_SECRET_LAST_USED_AT.lt(utcOrNull(unlessAfter))))
                .execute() == 1;
    }

    /// The keyed-hash migration (`docs/spec/encryption.md` §3): rewrites the
    /// current secret ref to `newRef` (its hashed form) — guarded on `oldRef`
    /// still being the stored value, so a concurrent rotation or another
    /// migration write is never clobbered. Runs in its own autocommit
    /// statement, like [#touchPreviousSecretUsed] — the auth flow's outcome
    /// must not depend on it.
    ///
    /// @return whether a row was rewritten
    public boolean rewriteSecretRef(String id, String oldRef, String newRef) {
        return dsl.update(T)
                .set(T.CLIENT_SECRET_REF, newRef)
                .where(T.ID.eq(id))
                .and(T.CLIENT_SECRET_REF.eq(oldRef))
                .execute() == 1;
    }

    /// [#rewriteSecretRef], for the previous (in-grace) secret ref — the
    /// secret and its rotation grace are untouched, only the stored ref's
    /// shape changes.
    ///
    /// @return whether a row was rewritten
    public boolean rewritePreviousSecretRef(String id, String oldRef, String newRef) {
        return dsl.update(T)
                .set(T.PREVIOUS_SECRET_REF, newRef)
                .where(T.ID.eq(id))
                .and(T.PREVIOUS_SECRET_REF.eq(oldRef))
                .execute() == 1;
    }

    /// The purger's sweep: a superseded secret whose overlap window has
    /// closed is cleared at rest (verification already refuses it). Returns
    /// the number of clients cleared.
    public int clearLapsedPreviousSecrets(Instant now) {
        return dsl.update(T)
                .setNull(T.PREVIOUS_SECRET_REF)
                .setNull(T.PREVIOUS_SECRET_EXPIRES_AT)
                .where(T.PREVIOUS_SECRET_REF.isNotNull())
                .and(T.PREVIOUS_SECRET_EXPIRES_AT.isNotNull())
                .and(T.PREVIOUS_SECRET_EXPIRES_AT.lt(utcOrNull(now)))
                .execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static OAuthClient toEntity(OauthClientsRecord row, List<String> redirectUris, List<String> postLogoutUris,
                                        List<String> grantTypes, List<String> allowedOrigins, List<String> applicationIds) {
        return new OAuthClient(
                row.getId(),
                row.getClientId(),
                row.getClientName(),
                clientType(row.getId(), row.getClientType()),
                row.getClientSecretRef(),
                row.getPreviousSecretRef(),
                instantOrNull(row.getPreviousSecretExpiresAt()),
                instantOrNull(row.getPreviousSecretLastUsedAt()),
                redirectUris,
                postLogoutUris,
                grantTypes,
                splitScopes(row.getDefaultScopes()),
                allowedOrigins,
                applicationIds,
                Boolean.TRUE.equals(row.getPkceRequired()),
                Boolean.TRUE.equals(row.getActive()),
                row.getServiceAccountPrincipalId(),
                row.getPortalClientId(),
                row.getPortalAppId(),
                Boolean.TRUE.equals(row.getApiAccess()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /// [ClientType#parse], wrapped so a corrupt stored value fails loudly
    /// with the offending row's id (X-06).
    private static ClientType clientType(String rowId, String stored) {
        try {
            return ClientType.parse(stored);
        } catch (ClientType.UnrecognisedClientTypeException e) {
            throw new CorruptOAuthClientException(rowId, e);
        }
    }

    /// `default_scopes` is a comma-joined string at rest (unchanged schema);
    /// the wire and the entity both use the array shape (Fix 5).
    private static List<String> splitScopes(String stored) {
        if (stored == null || stored.isEmpty()) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String s : stored.split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String joinScopes(List<String> scopes) {
        return scopes.isEmpty() ? null : String.join(",", scopes);
    }

    private static Instant instantOrNull(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utcOrNull(Instant instant) {
        return instant == null ? null : utc(instant);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.db.generated.tables.PortalIdentities;
import io.flowcatalyst.db.generated.tables.records.PortalIdentitiesRecord;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.flowcatalyst.db.generated.Tables.PORTAL_IDENTITIES;
import static io.flowcatalyst.db.generated.Tables.PORTAL_IDENTITY_APPS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/// `portal_identities` (+ its `portal_identity_apps` grants, spec
/// `portal-apps.md` §2.2) via jOOQ (spec `auth-identity.md` §3.3). Pure
/// CRUD — no business decisions live here. Writes happen only on the unit
/// of work's transaction ([Persist]); [#touchLastLogin] and [#markInvited]
/// are the exceptions (autocommit, best-effort statements outside any
/// transaction — the caller decides how to treat a failure, spec §14:
/// "security side effects… never fail the primary action").
public final class PortalIdentityRepository implements Persist<PortalIdentity> {

    private static final PortalIdentities T = PORTAL_IDENTITIES;

    private final DSLContext dsl;

    public PortalIdentityRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<PortalIdentity> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    public Optional<PortalIdentity> findByClientAndEmail(String clientId, String email) {
        return findOne(T.CLIENT_ID.eq(clientId).and(T.EMAIL.eq(PortalIdentity.normalizeEmail(email))));
    }

    /// One hydration path: every single-row lookup goes through this, the
    /// condition is the only thing that varies (CONVENTIONS §8).
    private Optional<PortalIdentity> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional()
                .map(row -> toEntity(row, grantsFor(List.of(row.getId())).getOrDefault(row.getId(), List.of())));
    }

    /// One query per page — never N (`portal-apps.md` §2.2).
    private Map<String, List<PortalAppGrant>> grantsFor(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dsl.selectFrom(PORTAL_IDENTITY_APPS).where(PORTAL_IDENTITY_APPS.IDENTITY_ID.in(ids))
                .fetch().stream().collect(groupingBy(r -> r.getIdentityId(),
                        mapping(r -> new PortalAppGrant(r.getPortalAppId(), PortalAppGrantSource.parse(r.getSource()),
                                r.getGrantedAt().toInstant()), toList())));
    }

    // ── Search (spec `portal-apps.md` §4.2, §9.3, Part A J10) ────────────────

    public record SearchFilter(String clientId, String q, String portalAppId, int page, int size) {
        public SearchFilter {
            Objects.requireNonNull(clientId, "clientId");
        }
    }

    public record SearchPage(List<PortalIdentity> items, long total) {
        public SearchPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /// Builds the `WHERE` dynamically — conditions added only when present
    /// (J10), never `($x = '' OR …)`, so the `text_pattern_ops` indexes
    /// apply. `q` is trimmed, lower-cased, LIKE-escaped and prefix-anchored
    /// (`q%`, never `%q%`). Page/size clamping is the API's job (unit A).
    public SearchPage search(SearchFilter filter) {
        Objects.requireNonNull(filter, "filter");
        Condition where = T.CLIENT_ID.eq(filter.clientId());
        if (filter.q() != null && !filter.q().isBlank()) {
            String pattern = likeEscape(filter.q().trim().toLowerCase(Locale.ROOT)) + "%";
            where = where.and(T.EMAIL.like(pattern, '\\').or(DSL.lower(T.NAME).like(pattern, '\\')));
        }
        if (filter.portalAppId() != null && !filter.portalAppId().isBlank()) {
            where = where.and(DSL.exists(dsl.selectOne().from(PORTAL_IDENTITY_APPS)
                    .where(PORTAL_IDENTITY_APPS.IDENTITY_ID.eq(T.ID))
                    .and(PORTAL_IDENTITY_APPS.PORTAL_APP_ID.eq(filter.portalAppId()))));
        }
        long total = dsl.selectCount().from(T).where(where).fetchOne(0, long.class);
        var rows = dsl.selectFrom(T).where(where)
                .orderBy(T.CREATED_AT.desc(), T.ID.desc())
                .limit(filter.size()).offset(filter.page() * filter.size())
                .fetch();
        Map<String, List<PortalAppGrant>> grants = grantsFor(rows.getValues(T.ID));
        List<PortalIdentity> items = rows.map(row -> toEntity(row, grants.getOrDefault(row.getId(), List.of())));
        return new SearchPage(items, total);
    }

    /// `\` → `\\`, `%` → `\%`, `_` → `\_` — the backslash first, so its own
    /// escaping never re-escapes the `%`/`_` markers it just produced.
    private static String likeEscape(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upsert `ON CONFLICT (client_id, email) DO UPDATE SET name, status,
    /// updated_at` (spec §3.3): a re-`Ensure` on an existing row keeps
    /// `id` / `source` / `created_at` / `password_hash` / `invited_at` /
    /// `invite_expires_at` **at the SQL level** — those columns are simply
    /// absent from the `SET` list, so the invariant holds regardless of
    /// what the in-memory aggregate happened to carry for them. The grant
    /// set is then synced to exactly [PortalIdentity#apps], keyed by the
    /// `RETURNING id` — the row a racing `Ensure` actually resolved to,
    /// which may differ from `pi.id()` (`portal-apps.md` §2.2).
    @Override
    public void persist(PortalIdentity pi, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        String resolvedId = txDsl.insertInto(T)
                .set(T.ID, pi.id())
                .set(T.CLIENT_ID, pi.clientId())
                .set(T.EMAIL, pi.email())
                .set(T.NAME, pi.name())
                .set(T.PASSWORD_HASH, pi.passwordHash())
                .set(T.STATUS, pi.status().name())
                .set(T.SOURCE, pi.source().name())
                .set(T.LAST_LOGIN_AT, utcOrNull(pi.lastLoginAt()))
                .set(T.CREATED_AT, utc(pi.createdAt()))
                .set(T.UPDATED_AT, utc(pi.updatedAt()))
                .onConflict(T.CLIENT_ID, T.EMAIL).doUpdate()
                .set(T.NAME, pi.name())
                .set(T.STATUS, pi.status().name())
                .set(T.UPDATED_AT, utc(pi.updatedAt()))
                .returning(T.ID)
                .fetchOne(T.ID);
        syncGrants(txDsl, resolvedId, pi.apps());
    }

    /// Deletes every `portal_identity_apps` row for `identityId` not in
    /// `apps`, then inserts whatever is missing (`ON CONFLICT DO NOTHING`) —
    /// exactly [PortalIdentity#apps], no more, no less.
    private void syncGrants(DSLContext txDsl, String identityId, List<PortalAppGrant> apps) {
        Set<String> keep = apps.stream().map(PortalAppGrant::appId).collect(toSet());
        var deleteWhere = PORTAL_IDENTITY_APPS.IDENTITY_ID.eq(identityId);
        txDsl.deleteFrom(PORTAL_IDENTITY_APPS)
                .where(keep.isEmpty() ? deleteWhere : deleteWhere.and(PORTAL_IDENTITY_APPS.PORTAL_APP_ID.notIn(keep)))
                .execute();
        for (PortalAppGrant g : apps) {
            txDsl.insertInto(PORTAL_IDENTITY_APPS)
                    .set(PORTAL_IDENTITY_APPS.IDENTITY_ID, identityId)
                    .set(PORTAL_IDENTITY_APPS.PORTAL_APP_ID, g.appId())
                    .set(PORTAL_IDENTITY_APPS.SOURCE, g.source().name())
                    .set(PORTAL_IDENTITY_APPS.GRANTED_AT, utc(g.grantedAt()))
                    .onConflict(PORTAL_IDENTITY_APPS.IDENTITY_ID, PORTAL_IDENTITY_APPS.PORTAL_APP_ID).doNothing()
                    .execute();
        }
    }

    @Override
    public void delete(PortalIdentity pi, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(pi.id())).execute();
    }

    /// Best-effort, autocommit (spec §5.3 row 9, §5.6): the caller must not
    /// let a failure here fail the sign-in it is recording.
    public void touchLastLogin(String id) {
        dsl.update(T).set(T.LAST_LOGIN_AT, DSL.currentOffsetDateTime()).where(T.ID.eq(id)).execute();
    }

    /// The reset unit's write, inside its own transaction (spec §3.3: "NULL
    /// until an invite/reset completes"). Not yet called from this unit.
    public void setPasswordHash(String id, String passwordHash, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).update(T)
                .set(T.PASSWORD_HASH, passwordHash)
                .set(T.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(T.ID.eq(id))
                .execute();
    }

    /// A direct, autocommit `UPDATE` (`portal-apps.md` §2.2): `invited_at` /
    /// `invite_expires_at` are never part of [#persist]'s upsert, so a later
    /// re-`Ensure` cannot clobber them. `expiresAtOrNull` = `null` records an
    /// SSO invite that never expires.
    public void markInvited(String id, Instant invitedAt, Instant expiresAtOrNull) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(invitedAt, "invitedAt");
        dsl.update(T)
                .set(T.INVITED_AT, utc(invitedAt))
                .set(T.INVITE_EXPIRES_AT, utcOrNull(expiresAtOrNull))
                .set(T.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(T.ID.eq(id))
                .execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static PortalIdentity toEntity(PortalIdentitiesRecord row, List<PortalAppGrant> grants) {
        return new PortalIdentity(
                row.getId(),
                row.getClientId(),
                row.getEmail(),
                row.getName(),
                row.getPasswordHash(),
                PortalIdentityStatus.parse(row.getStatus()),
                parseSource(row.getSource()),
                grants,
                instantOrNull(row.getLastLoginAt()),
                instantOrNull(row.getInvitedAt()),
                instantOrNull(row.getInviteExpiresAt()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static PortalIdentitySource parseSource(String stored) {
        return "JIT".equals(stored) ? PortalIdentitySource.JIT : PortalIdentitySource.INVITE;
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

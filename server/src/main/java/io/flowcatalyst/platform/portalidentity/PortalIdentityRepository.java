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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.PORTAL_IDENTITIES;

/// `portal_identities` via jOOQ (spec `auth-identity.md` §3.3). Pure CRUD —
/// no business decisions live here. Writes happen only on the unit of
/// work's transaction ([Persist]); [#touchLastLogin] is the one exception
/// (an autocommit, best-effort statement outside any transaction — the
/// caller decides how to treat a failure, spec §14: "security side effects…
/// never fail the primary action").
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

    /// Every identity of one client, newest first (spec §5.7 list route).
    public List<PortalIdentity> findByClient(String clientId) {
        return dsl.selectFrom(T).where(T.CLIENT_ID.eq(clientId)).orderBy(T.CREATED_AT.desc())
                .fetch().map(PortalIdentityRepository::toEntity);
    }

    private Optional<PortalIdentity> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(PortalIdentityRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upsert `ON CONFLICT (client_id, email) DO UPDATE SET name, status,
    /// updated_at` (spec §3.3): a re-`Ensure` on an existing row keeps
    /// `id` / `source` / `created_at` / `password_hash` **at the SQL level**
    /// — those columns are simply absent from the `SET` list, so the
    /// invariant holds regardless of what the in-memory aggregate happened
    /// to carry for them.
    @Override
    public void persist(PortalIdentity pi, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.insertInto(T)
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
                .execute();
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

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static PortalIdentity toEntity(PortalIdentitiesRecord row) {
        return new PortalIdentity(
                row.getId(),
                row.getClientId(),
                row.getEmail(),
                row.getName(),
                row.getPasswordHash(),
                PortalIdentityStatus.parse(row.getStatus()),
                parseSource(row.getSource()),
                instantOrNull(row.getLastLoginAt()),
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

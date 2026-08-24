package io.flowcatalyst.platform.principal;

import io.flowcatalyst.db.generated.tables.IamClientAccessGrants;
import io.flowcatalyst.db.generated.tables.IamPrincipalApplicationAccess;
import io.flowcatalyst.db.generated.tables.IamPrincipalRoles;
import io.flowcatalyst.db.generated.tables.IamPrincipals;
import io.flowcatalyst.db.generated.tables.IamRoles;
import io.flowcatalyst.db.generated.tables.records.IamPrincipalsRecord;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.SortField;
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

import static io.flowcatalyst.db.generated.Tables.IAM_CLIENT_ACCESS_GRANTS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_APPLICATION_ACCESS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_ROLES;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `iam_principals` + its three junctions via jOOQ (spec §9). Reads hydrate
/// roles, client grants and application access in one `IN` query each.
///
/// Writes: the base [#persist] writes the `iam_principals` row **only**, so
/// a writer that never loaded a junction cannot clobber it. The operation
/// that owns a junction chooses a composed persister — [#withRoles()],
/// [#withApplicationAccess()], [#withClientGrants] — which rewrites that
/// junction in the same transaction as the row and the domain event.
/// Client-access grants are otherwise their own aggregate
/// ([ClientAccessGrantRepository]). Pure CRUD — no domain decisions here.
public final class PrincipalRepository implements Persist<Principal> {

    private static final IamPrincipals P = IAM_PRINCIPALS;
    private static final IamPrincipalRoles PR = IAM_PRINCIPAL_ROLES;
    private static final IamClientAccessGrants G = IAM_CLIENT_ACCESS_GRANTS;
    private static final IamPrincipalApplicationAccess PA = IAM_PRINCIPAL_APPLICATION_ACCESS;
    private static final IamRoles R = IAM_ROLES;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public PrincipalRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Principal> findById(String id) {
        return findOne(P.ID.eq(id));
    }

    /// The USER with this email, matched case-insensitively on the
    /// normalised input (stored emails are lower-cased; legacy mixed-case rows
    /// still match).
    public Optional<Principal> findByEmail(String email) {
        return findOne(P.TYPE.eq(PrincipalType.USER.name()).and(DSL.lower(P.EMAIL).eq(EmailAddress.normalise(email))));
    }

    /// The SERVICE principal of a service account.
    public Optional<Principal> findByServiceAccount(String serviceAccountId) {
        return findOne(P.TYPE.eq(PrincipalType.SERVICE.name()).and(P.SERVICE_ACCOUNT_ID.eq(serviceAccountId)));
    }

    /// Every principal holding `roleName`, by name.
    public List<Principal> findByRole(String roleName) {
        var ids = dsl.select(PR.PRINCIPAL_ID).from(PR).where(PR.ROLE_NAME.eq(roleName));
        return findMany(P.ID.in(ids), P.NAME.asc());
    }

    /// Every USER on `domain` (lower-cased), by email.
    public List<Principal> findUsersByEmailDomain(String domain) {
        return findMany(P.TYPE.eq(PrincipalType.USER.name()).and(P.EMAIL_DOMAIN.eq(EmailAddress.normalise(domain))), P.EMAIL.asc());
    }

    /// Every principal, newest first.
    public List<Principal> findAll() {
        return findMany(DSL.noCondition(), P.CREATED_AT.desc());
    }

    /// When the principal — or any role it holds — last changed: the later of
    /// the row's `updated_at` and the max `updated_at` of its roles (a role's
    /// permissions change without the principal row changing). Empty when
    /// there is no such principal.
    public Optional<Instant> lookupVersion(String id) {
        var rolesUpdated = dsl.select(DSL.max(R.UPDATED_AT)).from(PR).join(R).on(R.NAME.eq(PR.ROLE_NAME))
                .where(PR.PRINCIPAL_ID.eq(P.ID));
        return dsl.select(DSL.greatest(P.UPDATED_AT, DSL.coalesce(rolesUpdated, P.UPDATED_AT)))
                .from(P).where(P.ID.eq(id))
                .fetchOptional(r -> r.value1().toInstant());
    }

    private Optional<Principal> findOne(Condition where) {
        return dsl.selectFrom(P).where(where).fetchOptional().map(row -> {
            List<String> ids = List.of(row.getId());
            return toEntity(row, rolesFor(ids).getOrDefault(row.getId(), List.of()),
                    grantsFor(ids).getOrDefault(row.getId(), List.of()),
                    applicationAccessFor(ids).getOrDefault(row.getId(), List.of()));
        });
    }

    private List<Principal> findMany(Condition where, SortField<?> order) {
        var rows = dsl.selectFrom(P).where(where).orderBy(order).fetch();
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.getValues(P.ID);
        var roles = rolesFor(ids);
        var grants = grantsFor(ids);
        var apps = applicationAccessFor(ids);
        return List.copyOf(rows.map(row -> toEntity(row, roles.getOrDefault(row.getId(), List.of()),
                grants.getOrDefault(row.getId(), List.of()), apps.getOrDefault(row.getId(), List.of()))));
    }

    /// Role assignments for many principals in one query, each list by `assigned_at`.
    private Map<String, List<RoleAssignment>> rolesFor(List<String> principalIds) {
        return dsl.select(PR.PRINCIPAL_ID, PR.ROLE_NAME, PR.ASSIGNMENT_SOURCE, PR.ASSIGNED_AT).from(PR)
                .where(PR.PRINCIPAL_ID.in(principalIds)).orderBy(PR.ASSIGNED_AT.asc())
                .fetch().stream()
                .collect(groupingBy(r -> r.get(PR.PRINCIPAL_ID),
                        mapping(r -> new RoleAssignment(r.get(PR.ROLE_NAME), r.get(PR.ASSIGNMENT_SOURCE), r.get(PR.ASSIGNED_AT).toInstant()), toList())));
    }

    /// Granted client ids for many principals in one query, each list by `client_id`.
    private Map<String, List<String>> grantsFor(List<String> principalIds) {
        return dsl.select(G.PRINCIPAL_ID, G.CLIENT_ID).from(G)
                .where(G.PRINCIPAL_ID.in(principalIds)).orderBy(G.CLIENT_ID.asc())
                .fetch().stream()
                .collect(groupingBy(r -> r.get(G.PRINCIPAL_ID), mapping(r -> r.get(G.CLIENT_ID), toList())));
    }

    /// Accessible application ids for many principals in one query, each list by `application_id`.
    private Map<String, List<String>> applicationAccessFor(List<String> principalIds) {
        return dsl.select(PA.PRINCIPAL_ID, PA.APPLICATION_ID).from(PA)
                .where(PA.PRINCIPAL_ID.in(principalIds)).orderBy(PA.APPLICATION_ID.asc())
                .fetch().stream()
                .collect(groupingBy(r -> r.get(PA.PRINCIPAL_ID), mapping(r -> r.get(PA.APPLICATION_ID), toList())));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the `iam_principals` row `ON CONFLICT (id)`; `created_at` is
    /// written once, `updated_at` is stamped `now()` here. The user identity
    /// is normalised on the way in (spec §9): email lower-cased + trimmed,
    /// `email_domain` derived, a USER without a provider is `INTERNAL`, an
    /// external identity wins for the two IdP columns. Junctions untouched.
    @Override
    public void persist(Principal p, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        UserIdentity u = p.userIdentity();
        String email = u == null ? null : EmailAddress.normalise(u.email());
        String provider = u == null ? null : u.provider();
        String externalId = u == null ? null : u.externalId();
        if (provider == null && p.isUser()) provider = UserIdentity.INTERNAL;
        if (p.externalIdentity() != null) {
            if (!p.externalIdentity().providerId().isEmpty()) provider = p.externalIdentity().providerId();
            externalId = p.externalIdentity().externalId();
        }

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(P.TYPE, p.type().name());
        row.put(P.SCOPE, p.scope().name());
        row.put(P.CLIENT_ID, p.clientId());
        row.put(P.APPLICATION_ID, p.applicationId());
        row.put(P.NAME, p.name());
        row.put(P.ACTIVE, p.active());
        row.put(P.EMAIL, email);
        row.put(P.EMAIL_DOMAIN, email == null ? null : EmailAddress.domainOf(email));
        row.put(P.IDP_TYPE, provider);
        row.put(P.EXTERNAL_IDP_ID, externalId);
        row.put(P.PASSWORD_HASH, u == null ? null : u.passwordHash());
        row.put(P.LAST_LOGIN_AT, u == null || u.lastLoginAt() == null ? null : utc(u.lastLoginAt()));
        row.put(P.SERVICE_ACCOUNT_ID, p.serviceAccountId());
        row.put(P.ALL_APPLICATIONS, p.allApplications());
        row.put(P.DEV_CLIENT_SECRET_REF, u == null ? null : u.devClientSecretRef());
        row.put(P.DEV_CLIENT_SECRET_UPDATED_AT, u == null || u.devClientSecretUpdatedAt() == null ? null : utc(u.devClientSecretUpdatedAt()));
        row.put(P.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(P)
                .set(P.ID, p.id())
                .set(P.CREATED_AT, utc(p.createdAt()))
                .set(row)
                .onConflict(P.ID).doUpdate().set(row)
                .execute();
    }

    /// Removes application access, client grants and roles, then the row.
    @Override
    public void delete(Principal p, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(PA).where(PA.PRINCIPAL_ID.eq(p.id())).execute();
        txDsl.deleteFrom(G).where(G.PRINCIPAL_ID.eq(p.id())).execute();
        txDsl.deleteFrom(PR).where(PR.PRINCIPAL_ID.eq(p.id())).execute();
        txDsl.deleteFrom(P).where(P.ID.eq(p.id())).execute();
    }

    // ── Composed persisters (row + one junction, same transaction) ─────────

    /// Row + `iam_principal_roles` rewritten from [Principal#roles()] (clear,
    /// then insert `ON CONFLICT (principal_id, role_name) DO UPDATE`, so a
    /// duplicate name in the set is a no-op). For the operations that own the
    /// role set: assign roles, IdP sync, SDK sync.
    public Persist<Principal> withRoles() {
        return new Composed((p, txDsl) -> {
            txDsl.deleteFrom(PR).where(PR.PRINCIPAL_ID.eq(p.id())).execute();
            for (RoleAssignment ra : p.roles()) {
                txDsl.insertInto(PR)
                        .set(PR.PRINCIPAL_ID, p.id())
                        .set(PR.ROLE_NAME, ra.role())
                        .set(PR.ASSIGNMENT_SOURCE, ra.assignmentSource())
                        .set(PR.ASSIGNED_AT, utc(ra.assignedAt()))
                        .onConflict(PR.PRINCIPAL_ID, PR.ROLE_NAME).doUpdate()
                        .set(PR.ASSIGNMENT_SOURCE, ra.assignmentSource())
                        .set(PR.ASSIGNED_AT, utc(ra.assignedAt()))
                        .execute();
            }
        });
    }

    /// Row + `iam_principal_application_access` rewritten from
    /// [Principal#accessibleApplicationIds()]. For assign-application-access.
    public Persist<Principal> withApplicationAccess() {
        return new Composed((p, txDsl) -> {
            txDsl.deleteFrom(PA).where(PA.PRINCIPAL_ID.eq(p.id())).execute();
            for (String appId : p.accessibleApplicationIds()) {
                txDsl.insertInto(PA)
                        .set(PA.PRINCIPAL_ID, p.id())
                        .set(PA.APPLICATION_ID, appId)
                        .onConflict(PA.PRINCIPAL_ID, PA.APPLICATION_ID).doNothing()
                        .execute();
            }
        });
    }

    /// Row + a client-access grant for each of `clientIds` that does not exist
    /// yet (`ON CONFLICT (principal_id, client_id) DO NOTHING`), recorded as
    /// granted by `grantedBy`. For the TO_PARTNER promotion, which must keep
    /// the old home client reachable atomically with the scope change; the
    /// grant rows carry no events of their own (spec §2).
    public Persist<Principal> withClientGrants(List<String> clientIds, String grantedBy) {
        List<String> ids = List.copyOf(clientIds);
        Objects.requireNonNull(grantedBy, "grantedBy");
        return new Composed((p, txDsl) -> {
            OffsetDateTime now = utc(Instant.now());
            for (String cid : ids) {
                ClientAccessGrant g = ClientAccessGrant.create(p.id(), cid, grantedBy);
                txDsl.insertInto(G)
                        .set(G.ID, g.id())
                        .set(G.PRINCIPAL_ID, g.principalId())
                        .set(G.CLIENT_ID, g.clientId())
                        .set(G.GRANTED_BY, g.grantedBy())
                        .set(G.GRANTED_AT, utc(g.grantedAt()))
                        .set(G.CREATED_AT, utc(g.createdAt()))
                        .set(G.UPDATED_AT, now)
                        .onConflict(G.PRINCIPAL_ID, G.CLIENT_ID).doNothing()
                        .execute();
            }
        });
    }

    /// A junction write that follows the row upsert on the same connection.
    @FunctionalInterface
    private interface JunctionWrite {
        void apply(Principal p, DSLContext txDsl);
    }

    /// Row upsert, then one junction rewrite; delete is the base delete.
    private final class Composed implements Persist<Principal> {
        private final JunctionWrite junction;

        Composed(JunctionWrite junction) {
            this.junction = junction;
        }

        @Override
        public void persist(Principal p, DbTx tx) {
            PrincipalRepository.this.persist(p, tx);
            junction.apply(p, DSL.using(tx.connection(), SQLDialect.POSTGRES));
        }

        @Override
        public void delete(Principal p, DbTx tx) {
            PrincipalRepository.this.delete(p, tx);
        }
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Principal toEntity(IamPrincipalsRecord row, List<RoleAssignment> roles, List<String> grants, List<String> apps) {
        PrincipalType type = PrincipalType.parse(row.getType());
        UserIdentity identity = type == PrincipalType.USER && row.getEmail() != null
                ? new UserIdentity(row.getEmail(), row.getIdpType(), row.getExternalIdpId(), row.getPasswordHash(),
                        instant(row.getLastLoginAt()), row.getDevClientSecretRef(), instant(row.getDevClientSecretUpdatedAt()))
                : null;
        ExternalIdentity external = row.getExternalIdpId() == null ? null
                : new ExternalIdentity(row.getIdpType() == null ? "" : row.getIdpType(), row.getExternalIdpId());
        return new Principal(
                row.getId(),
                type,
                UserScope.parse(row.getScope()),
                row.getClientId(),
                row.getApplicationId(),
                row.getName(),
                row.getActive(),
                identity,
                row.getServiceAccountId(),
                roles,
                grants,
                apps,
                row.getAllApplications(),
                external,
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

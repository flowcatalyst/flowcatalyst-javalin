package io.flowcatalyst.platform.emaildomainmapping;

import io.flowcatalyst.db.generated.tables.TntEmailDomainMappingAdditionalClients;
import io.flowcatalyst.db.generated.tables.TntEmailDomainMappingGrantedClients;
import io.flowcatalyst.db.generated.tables.TntEmailDomainMapping_2faMethods;
import io.flowcatalyst.db.generated.tables.TntEmailDomainMappings;
import io.flowcatalyst.db.generated.tables.records.TntEmailDomainMappingsRecord;
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

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_ADDITIONAL_CLIENTS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_GRANTED_CLIENTS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `tnt_email_domain_mappings` + its three live junction tables
/// (`additional_clients`, `granted_clients`, `2fa_methods`) via jOOQ. Reads
/// hydrate the junctions in one `IN` query each; writes happen only on the
/// unit of work's transaction ([Persist]) and replace the junction rows
/// wholesale (no FK cascade exists, spec §8). The legacy `allowed_roles`
/// junction and `sync_roles_from_idp` column are dead (role sync moved to
/// the identity provider) — the junction is only cleared on delete. Pure
/// CRUD — no domain decisions live here.
public final class EmailDomainMappingRepository implements Persist<EmailDomainMapping> {

    private static final TntEmailDomainMappings T = TNT_EMAIL_DOMAIN_MAPPINGS;
    private static final TntEmailDomainMappingAdditionalClients ADD = TNT_EMAIL_DOMAIN_MAPPING_ADDITIONAL_CLIENTS;
    private static final TntEmailDomainMappingGrantedClients GRANT = TNT_EMAIL_DOMAIN_MAPPING_GRANTED_CLIENTS;
    private static final TntEmailDomainMapping_2faMethods MFA = TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public EmailDomainMappingRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<EmailDomainMapping> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Exact match on the stored (normalised) domain.
    public Optional<EmailDomainMapping> findByEmailDomain(String emailDomain) {
        return findOne(T.EMAIL_DOMAIN.eq(emailDomain));
    }

    /// Every mapping routed to `identityProviderId`, by domain.
    public List<EmailDomainMapping> findByIdentityProvider(String identityProviderId) {
        return findMany(T.IDENTITY_PROVIDER_ID.eq(identityProviderId));
    }

    /// Every mapping, by domain.
    public List<EmailDomainMapping> findAll() {
        return findMany(DSL.noCondition());
    }

    private Optional<EmailDomainMapping> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional()
                .map(row -> toEntity(row, new Junctions(List.of(row.getId()))));
    }

    private List<EmailDomainMapping> findMany(Condition where) {
        var rows = dsl.selectFrom(T).where(where).orderBy(T.EMAIL_DOMAIN.asc()).fetch();
        if (rows.isEmpty()) return List.of();
        var junctions = new Junctions(rows.getValues(T.ID));
        return List.copyOf(rows.map(row -> toEntity(row, junctions)));
    }

    /// The three junctions for many mappings, one query each, rows in insertion (`id`) order.
    private final class Junctions {
        final Map<String, List<String>> additional;
        final Map<String, List<String>> granted;
        final Map<String, List<String>> methods;

        Junctions(List<String> mappingIds) {
            additional = dsl.select(ADD.EMAIL_DOMAIN_MAPPING_ID, ADD.CLIENT_ID).from(ADD)
                    .where(ADD.EMAIL_DOMAIN_MAPPING_ID.in(mappingIds)).orderBy(ADD.ID.asc()).fetch().stream()
                    .collect(groupingBy(r -> r.get(ADD.EMAIL_DOMAIN_MAPPING_ID), mapping(r -> r.get(ADD.CLIENT_ID), toList())));
            granted = dsl.select(GRANT.EMAIL_DOMAIN_MAPPING_ID, GRANT.CLIENT_ID).from(GRANT)
                    .where(GRANT.EMAIL_DOMAIN_MAPPING_ID.in(mappingIds)).orderBy(GRANT.ID.asc()).fetch().stream()
                    .collect(groupingBy(r -> r.get(GRANT.EMAIL_DOMAIN_MAPPING_ID), mapping(r -> r.get(GRANT.CLIENT_ID), toList())));
            methods = dsl.select(MFA.EMAIL_DOMAIN_MAPPING_ID, MFA.METHOD).from(MFA)
                    .where(MFA.EMAIL_DOMAIN_MAPPING_ID.in(mappingIds)).orderBy(MFA.ID.asc()).fetch().stream()
                    .collect(groupingBy(r -> r.get(MFA.EMAIL_DOMAIN_MAPPING_ID), mapping(r -> r.get(MFA.METHOD), toList())));
        }
    }

    // ── TEMPORARY cross-aggregate access (spec §8) ─────────────────────────
    // Reads into tables owned by the `identityprovider` aggregate, plus the
    // move's one write into tables owned by the `principal` aggregate; kept
    // here so this package has no compile-time dependency on units that have
    // not landed. Each member names its future owner; replace with that
    // aggregate's repository once it exists.

    /// TEMPORARY (owner: the `identityprovider` aggregate) — what this
    /// aggregate needs to know about an identity provider: its display name
    /// (response enrichment) and whether it is the internal (password)
    /// provider (the move's direction). Any type other than `OIDC` reads as
    /// internal, as the provider aggregate's lenient reader does.
    public record IdentityProviderRef(String id, String name, String type) {
        public boolean isInternal() {
            return !"OIDC".equals(type);
        }
    }

    public Optional<IdentityProviderRef> identityProvider(String id) {
        return dsl.select(OAUTH_IDENTITY_PROVIDERS.ID, OAUTH_IDENTITY_PROVIDERS.NAME, OAUTH_IDENTITY_PROVIDERS.TYPE)
                .from(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.eq(id))
                .fetchOptional(r -> new IdentityProviderRef(r.value1(), r.value2(), r.value3()));
    }

    /// TEMPORARY (owner: the `identityprovider` aggregate) — display names
    /// keyed by provider id for every id that exists.
    public Map<String, String> identityProviderNames(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return dsl.select(OAUTH_IDENTITY_PROVIDERS.ID, OAUTH_IDENTITY_PROVIDERS.NAME)
                .from(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.in(ids))
                .fetchMap(OAUTH_IDENTITY_PROVIDERS.ID, OAUTH_IDENTITY_PROVIDERS.NAME);
    }

    /// TEMPORARY (owner: the `principal` aggregate,
    /// `io.flowcatalyst.platform.principal`) — the move's principal reset
    /// (spec §2), the one write outside this aggregate's tables: every `USER`
    /// principal on `emailDomain` whose `idp_type` is `OIDC` is converted back
    /// to internal auth (`idp_type = INTERNAL`, `external_idp_id = NULL`) and
    /// its `IDP_SYNC`-sourced role rows are removed. Returns how many
    /// principals were converted. The string literals are that aggregate's
    /// enum constants, spelled here until it lands; then this becomes a call
    /// to its repository (a `resetToInternal(domain)` persister) and the
    /// principal's own reader decides the `idp_type` semantics.
    public int resetOidcUsersToInternal(String emailDomain, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        List<String> ids = txDsl.update(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.IDP_TYPE, "INTERNAL")
                .set(IAM_PRINCIPALS.EXTERNAL_IDP_ID, (String) null)
                .set(IAM_PRINCIPALS.UPDATED_AT, utc(Instant.now()))
                .where(IAM_PRINCIPALS.TYPE.eq("USER"))
                .and(IAM_PRINCIPALS.EMAIL_DOMAIN.eq(emailDomain))
                .and(IAM_PRINCIPALS.IDP_TYPE.eq("OIDC"))
                .returning(IAM_PRINCIPALS.ID)
                .fetch(IAM_PRINCIPALS.ID);
        if (!ids.isEmpty()) {
            txDsl.deleteFrom(IAM_PRINCIPAL_ROLES)
                    .where(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.in(ids))
                    .and(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE.eq("IDP_SYNC"))
                    .execute();
        }
        return ids.size();
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)` and replaces the three junctions
    /// wholesale. `created_at` is written once and never updated;
    /// `updated_at` is stamped `now()` here, not taken from the aggregate;
    /// `sync_roles_from_idp` is never written (spec §8).
    @Override
    public void persist(EmailDomainMapping m, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.EMAIL_DOMAIN, m.emailDomain());
        row.put(T.IDENTITY_PROVIDER_ID, m.identityProviderId());
        row.put(T.SCOPE_TYPE, m.scopeType().name());
        row.put(T.PRIMARY_CLIENT_ID, m.primaryClientId());
        row.put(T.REQUIRED_OIDC_TENANT_ID, m.requiredOidcTenantId());
        row.put(T.REQUIRE_2FA, m.twoFactor().required());
        row.put(T.REMEMBER_DEVICE_ENABLED, m.twoFactor().rememberDeviceEnabled());
        row.put(T.REMEMBER_DEVICE_DAYS, m.twoFactor().rememberDeviceDays());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, m.id())
                .set(T.CREATED_AT, utc(m.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();

        txDsl.deleteFrom(ADD).where(ADD.EMAIL_DOMAIN_MAPPING_ID.eq(m.id())).execute();
        txDsl.deleteFrom(GRANT).where(GRANT.EMAIL_DOMAIN_MAPPING_ID.eq(m.id())).execute();
        txDsl.deleteFrom(MFA).where(MFA.EMAIL_DOMAIN_MAPPING_ID.eq(m.id())).execute();
        for (String clientId : m.additionalClientIds()) {
            txDsl.insertInto(ADD).set(ADD.EMAIL_DOMAIN_MAPPING_ID, m.id()).set(ADD.CLIENT_ID, clientId).execute();
        }
        for (String clientId : m.grantedClientIds()) {
            txDsl.insertInto(GRANT).set(GRANT.EMAIL_DOMAIN_MAPPING_ID, m.id()).set(GRANT.CLIENT_ID, clientId).execute();
        }
        for (MfaMethod method : m.twoFactor().allowedMethods()) {
            txDsl.insertInto(MFA).set(MFA.EMAIL_DOMAIN_MAPPING_ID, m.id()).set(MFA.METHOD, method.name()).execute();
        }
    }

    /// Clears the three live junctions and the legacy `allowed_roles`
    /// junction, then removes the row (spec §8).
    @Override
    public void delete(EmailDomainMapping m, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(ADD).where(ADD.EMAIL_DOMAIN_MAPPING_ID.eq(m.id())).execute();
        txDsl.deleteFrom(GRANT).where(GRANT.EMAIL_DOMAIN_MAPPING_ID.eq(m.id())).execute();
        txDsl.deleteFrom(TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES)
                .where(TNT_EMAIL_DOMAIN_MAPPING_ALLOWED_ROLES.EMAIL_DOMAIN_MAPPING_ID.eq(m.id())).execute();
        txDsl.deleteFrom(MFA).where(MFA.EMAIL_DOMAIN_MAPPING_ID.eq(m.id())).execute();
        txDsl.deleteFrom(T).where(T.ID.eq(m.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static EmailDomainMapping toEntity(TntEmailDomainMappingsRecord row, Junctions j) {
        String id = row.getId();
        return new EmailDomainMapping(
                id,
                row.getEmailDomain(),
                row.getIdentityProviderId(),
                ScopeType.parse(row.getScopeType()),
                row.getPrimaryClientId(),
                j.additional.getOrDefault(id, List.of()),
                j.granted.getOrDefault(id, List.of()),
                row.getRequiredOidcTenantId(),
                new TwoFactorPolicy(
                        row.getRequire_2fa(),
                        MfaMethod.readStored(j.methods.getOrDefault(id, List.of())),
                        row.getRememberDeviceEnabled(),
                        row.getRememberDeviceDays()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

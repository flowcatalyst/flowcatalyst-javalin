package io.flowcatalyst.platform.identityprovider;

import io.flowcatalyst.db.generated.tables.OauthIdentityProviderAllowedDomains;
import io.flowcatalyst.db.generated.tables.OauthIdentityProviderAllowedRoles;
import io.flowcatalyst.db.generated.tables.OauthIdentityProviders;
import io.flowcatalyst.db.generated.tables.TntEmailDomainMappings;
import io.flowcatalyst.db.generated.tables.records.OauthIdentityProvidersRecord;
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

import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDER_ALLOWED_DOMAINS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDER_ALLOWED_ROLES;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/// `oauth_identity_providers` + its `allowed_roles` junction via jOOQ
/// (spec §8). `allowedEmailDomains` is derived on read from
/// `tnt_email_domain_mappings` — the mapping table owns domain → IdP
/// routing, so this repository only ever *reads* it (one `IN` query,
/// hydrated into the entity). Writes happen only on the unit of work's
/// transaction ([Persist]). Pure CRUD — no domain decisions live here.
public final class IdentityProviderRepository implements Persist<IdentityProvider> {

    private static final OauthIdentityProviders T = OAUTH_IDENTITY_PROVIDERS;
    private static final OauthIdentityProviderAllowedRoles ROLES = OAUTH_IDENTITY_PROVIDER_ALLOWED_ROLES;
    /// Legacy junction, dead since mappings took over routing; cleared on delete so migrated installs keep no orphans.
    private static final OauthIdentityProviderAllowedDomains LEGACY_DOMAINS = OAUTH_IDENTITY_PROVIDER_ALLOWED_DOMAINS;
    private static final TntEmailDomainMappings MAPPINGS = TNT_EMAIL_DOMAIN_MAPPINGS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public IdentityProviderRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<IdentityProvider> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Exact match on the stored code.
    public Optional<IdentityProvider> findByCode(String code) {
        return findOne(T.CODE.eq(code));
    }

    /// Every provider, by code.
    public List<IdentityProvider> findAll() {
        return findMany(DSL.noCondition());
    }

    private Optional<IdentityProvider> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional()
                .map(row -> toEntity(row, new Children(List.of(row.getId()))));
    }

    private List<IdentityProvider> findMany(Condition where) {
        var rows = dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch();
        var children = new Children(rows.map(OauthIdentityProvidersRecord::getId));
        return List.copyOf(rows.map(row -> toEntity(row, children)));
    }

    /// The child rows of a set of providers, each fetched in one `IN` query:
    /// the allowed-roles junction (by `role_id`) and the mapped domains (by
    /// `email_domain`) from the mapping table.
    private final class Children {
        private final Map<String, List<String>> roles;
        private final Map<String, List<String>> domains;

        Children(List<String> providerIds) {
            if (providerIds.isEmpty()) {
                roles = Map.of();
                domains = Map.of();
                return;
            }
            roles = dsl.select(ROLES.IDENTITY_PROVIDER_ID, ROLES.ROLE_ID).from(ROLES)
                    .where(ROLES.IDENTITY_PROVIDER_ID.in(providerIds)).orderBy(ROLES.ROLE_ID.asc()).fetch().stream()
                    .collect(groupingBy(r -> r.get(ROLES.IDENTITY_PROVIDER_ID), mapping(r -> r.get(ROLES.ROLE_ID), toList())));
            domains = dsl.select(MAPPINGS.IDENTITY_PROVIDER_ID, MAPPINGS.EMAIL_DOMAIN).from(MAPPINGS)
                    .where(MAPPINGS.IDENTITY_PROVIDER_ID.in(providerIds)).orderBy(MAPPINGS.EMAIL_DOMAIN.asc()).fetch().stream()
                    .collect(groupingBy(r -> r.get(MAPPINGS.IDENTITY_PROVIDER_ID), mapping(r -> r.get(MAPPINGS.EMAIL_DOMAIN), toList())));
        }

        List<String> rolesOf(String providerId) {
            return roles.getOrDefault(providerId, List.of());
        }

        List<String> domainsOf(String providerId) {
            return domains.getOrDefault(providerId, List.of());
        }
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)` and replaces the allowed-roles
    /// junction wholesale. `created_at` is written once and never updated;
    /// `updated_at` is stamped `now()` here, not taken from the aggregate.
    /// `allowedEmailDomains` is derived and deliberately not written (spec §8).
    @Override
    public void persist(IdentityProvider ip, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CODE, ip.code());
        row.put(T.NAME, ip.name());
        row.put(T.TYPE, ip.type().name());
        row.put(T.OIDC_ISSUER_URL, ip.oidcIssuerUrl());
        row.put(T.OIDC_CLIENT_ID, ip.oidcClientId());
        row.put(T.OIDC_CLIENT_SECRET_REF, ip.oidcClientSecretRef());
        row.put(T.OIDC_MULTI_TENANT, ip.oidcMultiTenant());
        row.put(T.OIDC_ISSUER_PATTERN, ip.oidcIssuerPattern());
        row.put(T.SYNC_ROLES_FROM_IDP, ip.syncRolesFromIdp());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, ip.id())
                .set(T.CREATED_AT, utc(ip.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();

        txDsl.deleteFrom(ROLES).where(ROLES.IDENTITY_PROVIDER_ID.eq(ip.id())).execute();
        for (String roleId : ip.allowedRoleIds()) {
            txDsl.insertInto(ROLES).set(ROLES.IDENTITY_PROVIDER_ID, ip.id()).set(ROLES.ROLE_ID, roleId).execute();
        }
    }

    /// Clears the allowed-roles junction and the legacy allowed-domains
    /// junction, then removes the row (spec §8). Mappings are not touched —
    /// the operation refuses to delete while any still routes here.
    @Override
    public void delete(IdentityProvider ip, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(LEGACY_DOMAINS).where(LEGACY_DOMAINS.IDENTITY_PROVIDER_ID.eq(ip.id())).execute();
        txDsl.deleteFrom(ROLES).where(ROLES.IDENTITY_PROVIDER_ID.eq(ip.id())).execute();
        txDsl.deleteFrom(T).where(T.ID.eq(ip.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static IdentityProvider toEntity(OauthIdentityProvidersRecord row, Children children) {
        String id = row.getId();
        return new IdentityProvider(
                id,
                row.getCode(),
                row.getName(),
                IdentityProviderType.parse(row.getType()),
                row.getOidcIssuerUrl(),
                row.getOidcClientId(),
                row.getOidcClientSecretRef(),
                row.getOidcMultiTenant(),
                row.getOidcIssuerPattern(),
                children.domainsOf(id),
                row.getSyncRolesFromIdp(),
                children.rolesOf(id),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

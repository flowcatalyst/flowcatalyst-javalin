package io.flowcatalyst.platform.authadmin;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import io.flowcatalyst.db.generated.tables.TntClientAuthConfigs;
import io.flowcatalyst.db.generated.tables.records.TntClientAuthConfigsRecord;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
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

import static io.flowcatalyst.db.generated.Tables.TNT_CLIENT_AUTH_CONFIGS;

/// `tnt_client_auth_configs` via jOOQ. `additional_client_ids` and
/// `granted_client_ids` live as JSONB arrays on the row, so a config is one
/// row in and one row out; writes happen only on the unit of work's
/// transaction ([Persist]). Pure CRUD — no domain decisions live here.
///
/// `config_type` / `auth_provider` reads are strict (spec §2, X-06): a row
/// outside either closed set fails the read, wrapped as
/// [CorruptClientAuthConfigException] — see [#configType] / [#authProvider].
public final class ClientAuthConfigRepository implements Persist<ClientAuthConfig> {

    private static final TntClientAuthConfigs T = TNT_CLIENT_AUTH_CONFIGS;
    private static final TypeReference<List<String>> CLIENT_IDS = new TypeReference<>() {
    };

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ClientAuthConfigRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<ClientAuthConfig> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Exact match on the stored (normalised) domain.
    public Optional<ClientAuthConfig> findByEmailDomain(String emailDomain) {
        return findOne(T.EMAIL_DOMAIN.eq(emailDomain));
    }

    /// Every config, by domain (spec §2).
    public List<ClientAuthConfig> findAll() {
        return findMany(DSL.noCondition());
    }

    private Optional<ClientAuthConfig> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(ClientAuthConfigRepository::toEntity);
    }

    private List<ClientAuthConfig> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.EMAIL_DOMAIN.asc()).fetch().map(ClientAuthConfigRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here, not taken from
    /// the aggregate (spec §2). Arrays are written as `[]`, never `null`.
    @Override
    public void persist(ClientAuthConfig c, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.EMAIL_DOMAIN, c.emailDomain());
        row.put(T.CONFIG_TYPE, c.configType().name());
        row.put(T.PRIMARY_CLIENT_ID, c.primaryClientId());
        row.put(T.ADDITIONAL_CLIENT_IDS, JSONB.jsonb(Json.write(c.additionalClientIds())));
        row.put(T.GRANTED_CLIENT_IDS, JSONB.jsonb(Json.write(c.grantedClientIds())));
        row.put(T.AUTH_PROVIDER, c.authProvider().name());
        row.put(T.OIDC_ISSUER_URL, c.oidcIssuerUrl());
        row.put(T.OIDC_CLIENT_ID, c.oidcClientId());
        row.put(T.OIDC_MULTI_TENANT, c.oidcMultiTenant());
        row.put(T.OIDC_ISSUER_PATTERN, c.oidcIssuerPattern());
        row.put(T.OIDC_CLIENT_SECRET_REF, c.oidcClientSecretRef());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, c.id())
                .set(T.CREATED_AT, utc(c.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Hard delete by id (spec §4.2).
    @Override
    public void delete(ClientAuthConfig c, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(c.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ClientAuthConfig toEntity(TntClientAuthConfigsRecord row) {
        String id = row.getId();
        return new ClientAuthConfig(
                id,
                row.getEmailDomain(),
                configType(id, row.getConfigType()),
                row.getPrimaryClientId(),
                clientIdsOf(row.getAdditionalClientIds()),
                clientIdsOf(row.getGrantedClientIds()),
                authProvider(id, row.getAuthProvider()),
                row.getOidcIssuerUrl(),
                row.getOidcClientId(),
                Boolean.TRUE.equals(row.getOidcMultiTenant()),
                row.getOidcIssuerPattern(),
                row.getOidcClientSecretRef(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /// `NULL` / empty JSONB → no clients (spec §6).
    private static List<String> clientIdsOf(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) return List.of();
        try {
            return Json.MAPPER.readValue(jsonb.data(), CLIENT_IDS);
        } catch (JacksonException e) {
            throw new IllegalStateException("tnt_client_auth_configs client id column is not a valid string array", e);
        }
    }

    /// [ConfigType#parse], wrapped so a corrupt stored value fails loudly
    /// with the offending row's id (X-06) instead of propagating a bare
    /// [ConfigType.UnrecognisedConfigTypeException] with no context.
    private static ConfigType configType(String rowId, String stored) {
        try {
            return ConfigType.parse(stored);
        } catch (ConfigType.UnrecognisedConfigTypeException e) {
            throw new CorruptClientAuthConfigException(rowId, e);
        }
    }

    /// [AuthProvider#parse], wrapped the same way as [#configType].
    private static AuthProvider authProvider(String rowId, String stored) {
        try {
            return AuthProvider.parse(stored);
        } catch (AuthProvider.UnrecognisedAuthProviderException e) {
            throw new CorruptClientAuthConfigException(rowId, e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

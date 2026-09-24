package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.db.generated.tables.IamPrincipalRoles;
import io.flowcatalyst.db.generated.tables.IamPrincipals;
import io.flowcatalyst.db.generated.tables.IamServiceAccounts;
import io.flowcatalyst.db.generated.tables.records.IamServiceAccountsRecord;
import io.flowcatalyst.platform.shared.encryption.Decryption;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.encryption.SecretRef;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.IAM_SERVICE_ACCOUNTS;

/// `iam_service_accounts` via jOOQ (spec §7). Reads hydrate the roles
/// projection from the linked `SERVICE` principal's `iam_principal_roles`
/// rows in one extra query (spec §9.1) — on single-row reads only, never on
/// [#findAll], matching the existing `principalId` list-omission decision.
/// Writes happen only on the unit of work's transaction ([Persist]).
///
/// **Encryption at rest (spec §11, Go fix 3):** `wh_auth_token_ref` and
/// `wh_signing_secret_ref` hold the plaintext webhook bearer token / HMAC
/// signing secret, encrypted here under the app key — the aggregate itself
/// always carries plaintext in memory (it must: the bearer is stamped on
/// outbound requests, so it must be reproducible, not just verifiable).
/// Encryption is `Optional` at construction; a write that actually carries a
/// plaintext credential with none configured fails loudly (`SECRET`) rather
/// than storing plaintext.
///
/// **Legacy plaintext upgrade (spec §11, Go drift `fdcd2c1`):** a database
/// created by Go may still hold plaintext in the `_ref` columns. Every
/// single-row read that finds plaintext rewrites it encrypted,
/// **compare-and-set** — `UPDATE … WHERE id = ? AND col = <plaintext seen>`
/// — so a racing rotation's new secret is never overwritten with a
/// re-encryption of the stale value this read saw. Best-effort: a failed
/// upgrade must not fail the read.
public final class ServiceAccountRepository implements Persist<ServiceAccount> {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceAccountRepository.class);

    private static final IamServiceAccounts T = IAM_SERVICE_ACCOUNTS;
    private static final IamPrincipalRoles PR = IAM_PRINCIPAL_ROLES;
    private static final IamPrincipals P = IAM_PRINCIPALS;

    private final DSLContext dsl;
    private final Optional<Encryption> encryption;

    public ServiceAccountRepository(DataSource dataSource, Optional<Encryption> encryption) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.encryption = Objects.requireNonNull(encryption, "encryption");
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<ServiceAccount> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The account `id` names — its own `sac_…` id, **or the id of the service
    /// principal linked to it**. `app_applications.service_account_id` holds
    /// the principal's id (`application.md` §1.1), and a connection an
    /// application's sync writes copies it (`code-first-connections.md` §3,
    /// Go does the same), so a stored "service account id" can be either kind.
    /// Every place that resolves a stored reference into the account that signs
    /// — the delivery signer and the signing-reach check alike — must use this,
    /// or the two disagree (a reference one reads as dangling, the other signs).
    public Optional<ServiceAccount> findByIdOrServicePrincipalId(String id) {
        Optional<ServiceAccount> direct = findById(id);
        if (direct.isPresent() || id == null) {
            return direct;
        }
        return dsl.select(P.SERVICE_ACCOUNT_ID).from(P).where(P.ID.eq(id)).and(P.SERVICE_ACCOUNT_ID.isNotNull())
                .fetchOptional(P.SERVICE_ACCOUNT_ID)
                .flatMap(this::findById);
    }

    public Optional<ServiceAccount> findByCode(String code) {
        return findOne(T.CODE.eq(code));
    }

    /// Every service account, by code. No roles hydration, no legacy-secret
    /// upgrade (spec §9.1, §11) — a list read must not fan out into N per-row
    /// lookups or N writes.
    public List<ServiceAccount> findAll() {
        var rows = dsl.selectFrom(T).orderBy(T.CODE.asc()).fetch();
        return List.copyOf(rows.map(this::toEntity));
    }

    /// The oldest active service account of `applicationId` — the scheduled-job
    /// dispatcher's outbound-credentials resolution (`docs/spec/scheduled-job-scheduler.md`
    /// §3 step 5). No roles hydration (the dispatcher needs only credentials);
    /// legacy-secret upgrade still runs, matching every other single-row read.
    public Optional<ServiceAccount> findFirstActiveByApplicationId(String applicationId) {
        return dsl.selectFrom(T)
                .where(T.APPLICATION_ID.eq(applicationId).and(T.ACTIVE.isTrue()))
                .orderBy(T.CREATED_AT.asc())
                .limit(1)
                .fetchOptional()
                .map(row -> {
                    ServiceAccount sa = toEntity(row);
                    upgradeLegacySecrets(row.getId(), row.getWhAuthTokenRef(), row.getWhSigningSecretRef());
                    return sa;
                });
    }

    private Optional<ServiceAccount> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(row -> {
            ServiceAccount sa = toEntity(row).withRoles(rolesFor(row.getId()));
            upgradeLegacySecrets(row.getId(), row.getWhAuthTokenRef(), row.getWhSigningSecretRef());
            return sa;
        });
    }

    /// Role assignments of the linked `SERVICE` principal, by role name — the
    /// same rows `/roles` reads, so the two routes cannot disagree (spec §9.1).
    private List<RoleAssignment> rolesFor(String serviceAccountId) {
        return dsl.select(PR.ROLE_NAME, PR.ASSIGNMENT_SOURCE, PR.ASSIGNED_AT)
                .from(PR)
                .join(P).on(P.ID.eq(PR.PRINCIPAL_ID))
                .where(P.SERVICE_ACCOUNT_ID.eq(serviceAccountId))
                .orderBy(PR.ROLE_NAME.asc())
                .fetch(r -> new RoleAssignment(r.get(PR.ROLE_NAME), null, r.get(PR.ASSIGNMENT_SOURCE),
                        r.get(PR.ASSIGNED_AT).toInstant(), null));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`; `created_at` is written once,
    /// `updated_at` is stamped `now()` here. `wh_credentials_regenerated_at`
    /// is always written `NULL` (Go never populates it either — spec §11).
    @Override
    public void persist(ServiceAccount sa, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        OffsetDateTime now = utc(Instant.now());
        WebhookCredentials creds = sa.webhookCredentials();

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CODE, sa.code());
        row.put(T.NAME, sa.name());
        row.put(T.DESCRIPTION, sa.description());
        row.put(T.APPLICATION_ID, sa.applicationId());
        row.put(T.ACTIVE, sa.active());
        row.put(T.WH_AUTH_TYPE, creds.authType().name());
        row.put(T.WH_AUTH_TOKEN_REF, encryptedRef(creds.token()));
        row.put(T.WH_SIGNING_SECRET_REF, encryptedRef(creds.signingSecret()));
        row.put(T.WH_SIGNING_ALGORITHM, creds.signingAlgorithm());
        row.put(T.WH_CREDENTIALS_CREATED_AT, utc(sa.createdAt()));
        row.put(T.WH_CREDENTIALS_REGENERATED_AT, null);
        row.put(T.LAST_USED_AT, sa.lastUsedAt() == null ? null : utc(sa.lastUsedAt()));
        row.put(T.UPDATED_AT, now);
        row.put(T.SCOPE, sa.scope());
        row.put(T.CLIENT_IDS, sa.clientIds().toArray(new String[0]));
        txDsl.insertInto(T)
                .set(T.ID, sa.id())
                .set(T.CREATED_AT, utc(sa.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Removes the row. No cascade into the linked principal or its roles —
    /// matches Go, which deletes only `iam_service_accounts`.
    @Override
    public void delete(ServiceAccount sa, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        txDsl.deleteFrom(T).where(T.ID.eq(sa.id())).execute();
    }

    /// Stamps `last_used_at = now()` (spec §9.2): handing out a bearer, or an
    /// outbound delivery resolving credentials, is a use. Best-effort and
    /// outside any surrounding transaction — an unknown id updates no row and
    /// this never throws, so a failed stamp can never fail the mint or
    /// delivery it is bookkeeping for.
    public void touchLastUsed(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            dsl.update(T).set(T.LAST_USED_AT, utc(Instant.now())).where(T.ID.eq(id)).execute();
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("failed to stamp last_used_at for service account")
                    .addKeyValue("id", id)
                    .setCause(e)
                    .log();
        }
    }

    // ── Secrets at rest (spec §11) ───────────────────────────────────────────

    /// Encrypts a plaintext webhook credential for storage; `null` stays `null`.
    ///
    /// @throws UseCaseException internal `SECRET` when a plaintext credential
    ///                          is given but no app key is configured
    /// @throws UseCaseException validation `INVALID_SECRET_REF` when the value
    ///                          claims a secret-manager scheme that is not supported,
    ///                          or is an `encrypted:` claim that is not base64
    private String encryptedRef(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (encryption.isEmpty()) {
            throw UseCaseException.internal("SECRET",
                    "FLOWCATALYST_APP_KEY not configured; cannot encrypt webhook credential", null);
        }
        try {
            return encryption.get().encryptSecretRef(plaintext);
        } catch (IllegalArgumentException e) {
            // A malformed `encrypted:` claim or an unknown `<scheme>://` is the
            // caller's input, not a server fault: 400, not 500.
            throw UseCaseException.validation("INVALID_SECRET_REF", e.getMessage());
        }
    }

    /// Only an `encrypted:`-prefixed value is decrypted; anything else is
    /// legacy plaintext (or encryption is unconfigured) and is returned
    /// unchanged — matches Go's `decryptSecretRef` exactly, including "a value
    /// that fails to decrypt is returned unchanged rather than dropped"
    /// (spec §11: silently blanking a credential would break deliveries with
    /// no signal, where passing it through fails loudly at the far end).
    private String decryptStored(String stored) {
        if (stored == null || stored.isEmpty() || encryption.isEmpty()) {
            return stored;
        }
        SecretRef ref;
        try {
            ref = SecretRef.parse(stored);
        } catch (IllegalArgumentException e) {
            return stored;
        }
        if (!(ref instanceof SecretRef.Encrypted)) {
            return stored;
        }
        return switch (encryption.get().decrypt(stored)) {
            case Decryption.Plaintext(var pt) -> pt;
            case Decryption.External _, Decryption.Failed _ -> stored;
        };
    }

    /// Compare-and-set upgrade of both `_ref` columns from the values a read
    /// just saw (spec §11, drift `fdcd2c1`). Package-private and split from
    /// the raw jOOQ row (rather than taking `IamServiceAccountsRecord`
    /// directly) so a test can pin the race with a synthetic "as seen"
    /// snapshot, decoupled from the read that produced it — the same seam
    /// Go's own test uses (`reencryptLegacySecrets(ctx, seen)`).
    void upgradeLegacySecrets(String id, String tokenRefSeen, String signingSecretRefSeen) {
        if (encryption.isEmpty()) {
            return;
        }
        upgradeLegacySecret(id, T.WH_AUTH_TOKEN_REF, tokenRefSeen);
        upgradeLegacySecret(id, T.WH_SIGNING_SECRET_REF, signingSecretRefSeen);
    }

    private void upgradeLegacySecret(String id, Field<String> column, String stored) {
        if (stored == null || stored.isEmpty() || stored.startsWith(SecretRef.ENCRYPTED_PREFIX)) {
            return;
        }
        String blob;
        try {
            blob = SecretRef.ENCRYPTED_PREFIX + encryption.get().encrypt(stored);
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("failed to encrypt legacy service account secret")
                    .addKeyValue("id", id)
                    .addKeyValue("column", column.getName())
                    .setCause(e)
                    .log();
            return;
        }
        try {
            // Compare-and-set: rewrite only while the column still holds exactly the
            // plaintext this read saw, so a racing rotation's new secret survives.
            dsl.update(T).set(column, blob).where(T.ID.eq(id).and(column.eq(stored))).execute();
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("failed to upgrade legacy service account secret")
                    .addKeyValue("id", id)
                    .addKeyValue("column", column.getName())
                    .setCause(e)
                    .log();
        }
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    /// @throws CorruptServiceAccountException `row.wh_auth_type` is not
    ///                                        recognised by [WebhookAuthType#parse]
    ///                                        (spec §11, X-06)
    private ServiceAccount toEntity(IamServiceAccountsRecord row) {
        WebhookAuthType authType;
        try {
            authType = WebhookAuthType.parse(row.getWhAuthType());
        } catch (WebhookAuthType.UnrecognisedAuthTypeException e) {
            throw new CorruptServiceAccountException(row.getId(), e);
        }
        WebhookCredentials creds = new WebhookCredentials(authType,
                decryptStored(row.getWhAuthTokenRef()), null, null, null,
                decryptStored(row.getWhSigningSecretRef()), row.getWhSigningAlgorithm(), null);
        return new ServiceAccount(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getDescription(),
                row.getActive(),
                row.getClientIds() == null ? List.of() : List.of(row.getClientIds()),
                row.getScope(),
                row.getApplicationId(),
                creds,
                List.of(),
                instant(row.getLastUsedAt()),
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

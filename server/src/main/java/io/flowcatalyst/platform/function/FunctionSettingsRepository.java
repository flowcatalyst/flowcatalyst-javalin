package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnConfig;
import io.flowcatalyst.db.generated.tables.FnSecrets;
import io.flowcatalyst.platform.shared.encryption.Decryption;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static io.flowcatalyst.db.generated.Tables.FN_CONFIG;
import static io.flowcatalyst.db.generated.Tables.FN_SECRETS;

/// `fn_config` + `fn_secrets` via jOOQ (spec `function-context.md` §1, D4a).
/// Per-function, per-key settings that outlive a deploy — written by the
/// `SetFunctionConfig`/`SetFunctionSecret`/`DeleteFunctionSecret` TxOperations
/// on their own open transaction ([DbTx]), not through
/// [io.flowcatalyst.sdk.usecase.jdbc.Persist]: there is no single aggregate
/// here, just per-key rows a use case writes alongside its event.
///
/// **Encryption at rest (spec §1):** `fn_secrets.value_ref` holds
/// [Encryption]'s `encrypted:` form, exactly as `ServiceAccountRepository`
/// stores webhook credentials — never plaintext. Encryption is `Optional` at
/// construction; [#putSecret] fails loudly (`SECRET`) rather than ever
/// writing plaintext when no app key is configured. `FunctionApi`'s own 503
/// `ENCRYPTION_UNCONFIGURED` gate is what a caller actually sees for a
/// write; this repository's guard is the second line of defence, and
/// [#decryptSecrets] simply omits anything it cannot decrypt (spec §1: "no
/// app key ⇒ desired state carries no secrets" — the missing entry then
/// shows up as `missingSettings`, not a 500).
public final class FunctionSettingsRepository {

    private static final FnConfig C = FN_CONFIG;
    private static final FnSecrets S = FN_SECRETS;

    private final DSLContext dsl;
    private final Optional<Encryption> encryption;

    public FunctionSettingsRepository(DataSource dataSource, Optional<Encryption> encryption) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.encryption = Objects.requireNonNull(encryption, "encryption");
    }

    // ── config ────────────────────────────────────────────────────────────

    /// Every `(key, value)` for `functionId`, key order — the GET response's
    /// `values` map.
    public Map<String, String> configMap(String functionId) {
        Objects.requireNonNull(functionId, "functionId");
        Map<String, String> out = new TreeMap<>();
        dsl.select(C.KEY, C.VALUE).from(C).where(C.FUNCTION_ID.eq(functionId))
                .fetch().forEach(r -> out.put(r.value1(), r.value2()));
        return out;
    }

    /// Full replacement of `functionId`'s config (spec §1's `PUT`): every key
    /// not in `values` is deleted, every key in `values` is upserted — on the
    /// caller's open transaction.
    public void replaceConfig(String functionId, Map<String, String> values, String updatedBy, DbTx tx) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(tx, "tx");
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        OffsetDateTime now = utc(Instant.now());
        if (values.isEmpty()) {
            txDsl.deleteFrom(C).where(C.FUNCTION_ID.eq(functionId)).execute();
            return;
        }
        txDsl.deleteFrom(C).where(C.FUNCTION_ID.eq(functionId)).and(C.KEY.notIn(values.keySet())).execute();
        for (var e : values.entrySet()) {
            txDsl.insertInto(C)
                    .set(C.FUNCTION_ID, functionId)
                    .set(C.KEY, e.getKey())
                    .set(C.VALUE, e.getValue())
                    .set(C.UPDATED_BY, updatedBy)
                    .set(C.UPDATED_AT, now)
                    .onConflict(C.FUNCTION_ID, C.KEY).doUpdate()
                    .set(C.VALUE, e.getValue())
                    .set(C.UPDATED_BY, updatedBy)
                    .set(C.UPDATED_AT, now)
                    .execute();
        }
    }

    // ── secrets ───────────────────────────────────────────────────────────

    /// One entry of `GET .../secrets` (spec §1) — key, `updatedAt`,
    /// `updatedBy`, **never a value**.
    public record SecretInfo(String key, Instant updatedAt, String updatedBy) {
    }

    /// Every secret's metadata for `functionId`, key order — never a value
    /// (spec §1: "never a value, in any response").
    public List<SecretInfo> listSecrets(String functionId) {
        Objects.requireNonNull(functionId, "functionId");
        return dsl.select(S.KEY, S.UPDATED_AT, S.UPDATED_BY).from(S)
                .where(S.FUNCTION_ID.eq(functionId))
                .orderBy(S.KEY.asc())
                .fetch(r -> new SecretInfo(r.value1(), r.value2().toInstant(), r.value3()));
    }

    /// Every secret KEY set for `functionId` — the `declared`/`missing`
    /// computation, never a value.
    public Set<String> secretKeySet(String functionId) {
        Objects.requireNonNull(functionId, "functionId");
        return Set.copyOf(dsl.select(S.KEY).from(S).where(S.FUNCTION_ID.eq(functionId)).fetchSet(S.KEY));
    }

    /// `true` when `functionId` has a secret named `key` — the DELETE
    /// route's 404 check.
    public boolean hasSecret(String functionId, String key) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(key, "key");
        return dsl.fetchExists(dsl.selectOne().from(S).where(S.FUNCTION_ID.eq(functionId)).and(S.KEY.eq(key)));
    }

    /// Encrypts `value` and upserts one row, on the caller's open transaction.
    ///
    /// @throws UseCaseException internal `SECRET` when no app key is
    ///                          configured — `FunctionApi`'s 503 gate keeps
    ///                          this from firing on production traffic, but
    ///                          the repository never trusts that and refuses
    ///                          to write plaintext regardless.
    public void putSecret(String functionId, String key, SecretValue value, String updatedBy, DbTx tx) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(tx, "tx");
        String ref = encryptedRef(value.value());
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        OffsetDateTime now = utc(Instant.now());
        txDsl.insertInto(S)
                .set(S.FUNCTION_ID, functionId)
                .set(S.KEY, key)
                .set(S.VALUE_REF, ref)
                .set(S.UPDATED_BY, updatedBy)
                .set(S.UPDATED_AT, now)
                .onConflict(S.FUNCTION_ID, S.KEY).doUpdate()
                .set(S.VALUE_REF, ref)
                .set(S.UPDATED_BY, updatedBy)
                .set(S.UPDATED_AT, now)
                .execute();
    }

    /// Deletes one secret row; `true` when a row was actually removed (the
    /// API's 404-vs-204 decision), on the caller's open transaction.
    public boolean deleteSecret(String functionId, String key, DbTx tx) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tx, "tx");
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        int deleted = txDsl.deleteFrom(S).where(S.FUNCTION_ID.eq(functionId)).and(S.KEY.eq(key)).execute();
        return deleted > 0;
    }

    /// Decrypts every stored secret of `functionId` whose key is in `keys`
    /// (spec §1's desired-state restriction: "restricted to the keys that
    /// entry's own manifest declares") — [DesiredState]'s one caller. A key
    /// with no row, or whose `value_ref` fails to decrypt, is simply absent
    /// from the result (it surfaces as `missingSettings`, never a 500).
    /// Empty `keys`, or no app key configured, reads nothing.
    public Map<String, String> decryptSecrets(String functionId, Set<String> keys) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty() || encryption.isEmpty()) {
            return Map.of();
        }
        Encryption enc = encryption.get();
        Map<String, String> out = new TreeMap<>();
        dsl.select(S.KEY, S.VALUE_REF).from(S)
                .where(S.FUNCTION_ID.eq(functionId)).and(S.KEY.in(keys))
                .fetch().forEach(r -> {
                    switch (enc.decrypt(r.value2())) {
                        case Decryption.Plaintext(var pt) -> out.put(r.value1(), pt);
                        case Decryption.External _, Decryption.Failed _ -> {
                            // dropped: surfaces as missingSettings, never a 500 (spec §1)
                        }
                    }
                });
        return out;
    }

    private String encryptedRef(String plaintext) {
        if (encryption.isEmpty()) {
            throw UseCaseException.internal("SECRET",
                    "FLOWCATALYST_APP_KEY not configured; cannot encrypt function secret", null);
        }
        try {
            return encryption.get().encryptSecretRef(plaintext);
        } catch (IllegalArgumentException e) {
            throw UseCaseException.validation("INVALID_SECRET_REF", e.getMessage());
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

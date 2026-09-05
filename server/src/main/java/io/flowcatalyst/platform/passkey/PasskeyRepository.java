package io.flowcatalyst.platform.passkey;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.WEBAUTHN_CREDENTIALS;

/// `webauthn_credentials` (§3.5): the upsert Go runs, the credential-id
/// lookup an assertion needs, and the per-principal list that skips legacy
/// rows with a warning (§7.6 — the user re-registers).
public final class PasskeyRepository implements Persist<Passkey> {

    private static final Logger LOG = LoggerFactory.getLogger(PasskeyRepository.class);
    private static final io.flowcatalyst.db.generated.tables.WebauthnCredentials T = WEBAUTHN_CREDENTIALS;

    private final DSLContext dsl;

    public PasskeyRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Empty for an unknown id; a legacy row reads as an error the caller
    /// surfaces (Go: `FindByID` → error).
    public Optional<Passkey> findById(String id) {
        return dsl.selectFrom(T).where(T.ID.eq(id)).fetchOptional().map(r -> {
            Passkey p = toEntity(r.getId(), r.getPrincipalId(), r.getCredentialId(), r.getPasskeyData(), r.getName(),
                    r.getCreatedAt(), r.getLastUsedAt());
            if (p == null) {
                throw new LegacyPasskeyException(r.getId());
            }
            return p;
        });
    }

    /// Empty for unknown and for legacy (Go: `FindByCredentialID` → not found).
    public Optional<Passkey> findByCredentialId(byte[] credentialId) {
        return dsl.selectFrom(T).where(T.CREDENTIAL_ID.eq(credentialId)).fetchOptional()
                .map(r -> toEntity(r.getId(), r.getPrincipalId(), r.getCredentialId(), r.getPasskeyData(), r.getName(),
                        r.getCreatedAt(), r.getLastUsedAt()));
    }

    public List<Passkey> findByPrincipal(String principalId) {
        var out = new ArrayList<Passkey>();
        for (var r : dsl.selectFrom(T).where(T.PRINCIPAL_ID.eq(principalId)).orderBy(T.CREATED_AT, T.ID).fetch()) {
            Passkey p = toEntity(r.getId(), r.getPrincipalId(), r.getCredentialId(), r.getPasskeyData(), r.getName(),
                    r.getCreatedAt(), r.getLastUsedAt());
            if (p == null) {
                LOG.warn("skipping legacy webauthn passkey credential={} principal={}", r.getId(), principalId);
                continue;
            }
            out.add(p);
        }
        return out;
    }

    @Override
    public void persist(Passkey p, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        JSONB data = JSONB.jsonb(Json.write(p.toStoredJson()));
        txDsl.insertInto(T)
                .set(T.ID, p.id()).set(T.PRINCIPAL_ID, p.principalId()).set(T.CREDENTIAL_ID, p.credentialId())
                .set(T.PASSKEY_DATA, data).set(T.NAME, p.name())
                .set(T.CREATED_AT, utc(p.createdAt())).set(T.LAST_USED_AT, p.lastUsedAt() == null ? null : utc(p.lastUsedAt()))
                .onConflict(T.ID).doUpdate()
                .set(T.PRINCIPAL_ID, p.principalId()).set(T.CREDENTIAL_ID, p.credentialId())
                .set(T.PASSKEY_DATA, data).set(T.NAME, p.name())
                .set(T.LAST_USED_AT, p.lastUsedAt() == null ? null : utc(p.lastUsedAt()))
                .execute();
    }

    @Override
    public void delete(Passkey p, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(p.id())).execute();
    }

    /// A stored blob from the previous library (§7.6).
    public static final class LegacyPasskeyException extends RuntimeException {
        public LegacyPasskeyException(String id) {
            super("credential " + id + " is a legacy passkey; re-registration required");
        }
    }

    private static Passkey toEntity(String id, String principalId, byte[] credentialId, JSONB data, String name,
                                    OffsetDateTime createdAt, OffsetDateTime lastUsedAt) {
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(data.data());
        } catch (RuntimeException e) {
            return null;
        }
        return Passkey.fromStoredJson(id, principalId, credentialId, node, name, createdAt.toInstant(),
                lastUsedAt == null ? null : lastUsedAt.toInstant());
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}

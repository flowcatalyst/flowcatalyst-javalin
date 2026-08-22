package io.flowcatalyst.platform.application;

import io.flowcatalyst.db.generated.tables.AppClientConfigs;
import io.flowcatalyst.db.generated.tables.records.AppClientConfigsRecord;
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
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.APP_CLIENT_CONFIGS;

/// `app_client_configs` via jOOQ — the [ClientConfig] aggregate's store.
/// Reads go through the pool; writes only on the unit of work's transaction.
public final class ClientConfigRepository implements Persist<ClientConfig> {

    private static final AppClientConfigs T = APP_CLIENT_CONFIGS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ClientConfigRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    /// The config for the (application, client) pair. The table has no unique
    /// constraint on the pair (spec §1.2); the first by creation wins.
    public Optional<ClientConfig> findByApplicationAndClient(String applicationId, String clientId) {
        return dsl.selectFrom(T)
                .where(T.APPLICATION_ID.eq(applicationId).and(T.CLIENT_ID.eq(clientId)))
                .orderBy(T.CREATED_AT.asc())
                .limit(1)
                .fetchOptional()
                .map(ClientConfigRepository::toEntity);
    }

    /// Every config of one application, by creation time.
    public List<ClientConfig> findByApplication(String applicationId) {
        return findMany(T.APPLICATION_ID.eq(applicationId));
    }

    /// Every (application) config of one client, by creation time.
    public List<ClientConfig> findByClient(String clientId) {
        return findMany(T.CLIENT_ID.eq(clientId));
    }

    private List<ClientConfig> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.CREATED_AT.asc()).fetch()
                .map(ClientConfigRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts `ON CONFLICT (id)`: only `enabled` and `updated_at` change on
    /// an existing row; the pair and `created_at` are insert-only (spec §9).
    @Override
    public void persist(ClientConfig c, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.ENABLED, c.enabled());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, c.id())
                .set(T.APPLICATION_ID, c.applicationId())
                .set(T.CLIENT_ID, c.clientId())
                .set(T.CREATED_AT, utc(c.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(ClientConfig c, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(c.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ClientConfig toEntity(AppClientConfigsRecord row) {
        return new ClientConfig(
                row.getId(),
                row.getApplicationId(),
                row.getClientId(),
                row.getEnabled(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

package io.flowcatalyst.platform.principal;

import io.flowcatalyst.db.generated.tables.IamClientAccessGrants;
import io.flowcatalyst.db.generated.tables.records.IamClientAccessGrantsRecord;
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

import static io.flowcatalyst.db.generated.Tables.IAM_CLIENT_ACCESS_GRANTS;

/// `iam_client_access_grants` via jOOQ (spec §9). A grant is its own
/// aggregate root so grant/revoke flow through the envelope with their own
/// events. Writes happen only on the unit of work's transaction.
public final class ClientAccessGrantRepository implements Persist<ClientAccessGrant> {

    private static final IamClientAccessGrants G = IAM_CLIENT_ACCESS_GRANTS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ClientAccessGrantRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<ClientAccessGrant> findByPrincipalAndClient(String principalId, String clientId) {
        return findOne(G.PRINCIPAL_ID.eq(principalId).and(G.CLIENT_ID.eq(clientId)));
    }

    /// A principal's grants, oldest first.
    public List<ClientAccessGrant> findByPrincipal(String principalId) {
        return List.copyOf(dsl.selectFrom(G).where(G.PRINCIPAL_ID.eq(principalId)).orderBy(G.GRANTED_AT.asc())
                .fetch().map(ClientAccessGrantRepository::toEntity));
    }

    private Optional<ClientAccessGrant> findOne(Condition where) {
        return dsl.selectFrom(G).where(where).fetchOptional().map(ClientAccessGrantRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts `ON CONFLICT (id)`, refreshing `granted_by` and `updated_at`.
    @Override
    public void persist(ClientAccessGrant g, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(G.GRANTED_BY, g.grantedBy());
        row.put(G.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(G)
                .set(G.ID, g.id())
                .set(G.PRINCIPAL_ID, g.principalId())
                .set(G.CLIENT_ID, g.clientId())
                .set(G.GRANTED_AT, utc(g.grantedAt()))
                .set(G.CREATED_AT, utc(g.createdAt()))
                .set(row)
                .onConflict(G.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(ClientAccessGrant g, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(G).where(G.ID.eq(g.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ClientAccessGrant toEntity(IamClientAccessGrantsRecord r) {
        return new ClientAccessGrant(r.getId(), r.getPrincipalId(), r.getClientId(), r.getGrantedBy(),
                r.getGrantedAt().toInstant(), r.getCreatedAt().toInstant(), r.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

package io.flowcatalyst.platform.connection;

import io.flowcatalyst.db.generated.tables.MsgConnections;
import io.flowcatalyst.db.generated.tables.records.MsgConnectionsRecord;
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

import static io.flowcatalyst.db.generated.Tables.MSG_CONNECTIONS;

/// `msg_connections` via jOOQ. Writes happen only on the unit of work's
/// transaction ([Persist]). Pure CRUD — no domain decisions live here.
public final class ConnectionRepository implements Persist<Connection> {

    private static final MsgConnections T = MSG_CONNECTIONS;

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ConnectionRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    /// Equality filters for [#findWithFilters]; `null` = no filter on that column.
    public record ListFilter(String status, String clientId) {
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Connection> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// The connection with `code` under the three-part key
    /// `(applicationCode, clientId, code)` — `NULL` is a real value on both
    /// nullable parts, never a wildcard (spec `code-first-connections.md` §2):
    /// `(A, null, code)` does not match the shared `(null, null, code)` row,
    /// nor another client's `(A, B, code)` row. Every part compares with
    /// `isNotDistinctFrom`, `code` included (spec §2: "every lookup"), even
    /// though `code` is `NOT NULL` at the schema level, so `eq` and
    /// `isNotDistinctFrom` are equivalent for it in practice.
    public Optional<Connection> findByCode(String code, String applicationCode, String clientId) {
        return findOne(T.CODE.isNotDistinctFrom(code)
                .and(T.APPLICATION_CODE.isNotDistinctFrom(applicationCode))
                .and(T.CLIENT_ID.isNotDistinctFrom(clientId)));
    }

    /// Every connection owned by `(applicationCode, clientId)` — `NULL`
    /// client matches `NULL` only (spec §2) — the set a connection sync
    /// reconciles against.
    public List<Connection> findByApplicationAndClient(String applicationCode, String clientId) {
        return findMany(T.APPLICATION_CODE.isNotDistinctFrom(applicationCode)
                .and(T.CLIENT_ID.isNotDistinctFrom(clientId)));
    }

    /// Connections matching every non-null filter, by code.
    public List<Connection> findWithFilters(ListFilter f) {
        Condition where = DSL.noCondition();
        if (f.status() != null) where = where.and(T.STATUS.eq(f.status()));
        if (f.clientId() != null) where = where.and(T.CLIENT_ID.eq(f.clientId()));
        return findMany(where);
    }

    private Optional<Connection> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(ConnectionRepository::toEntity);
    }

    private List<Connection> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.CODE.asc()).fetch().map(ConnectionRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here, not taken from
    /// the aggregate (spec §8).
    @Override
    public void persist(Connection c, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.CODE, c.code());
        row.put(T.APPLICATION_CODE, c.applicationCode());
        row.put(T.NAME, c.name());
        row.put(T.DESCRIPTION, c.description());
        row.put(T.EXTERNAL_ID, c.externalId());
        row.put(T.STATUS, c.status().name());
        row.put(T.SOURCE, c.source().name());
        row.put(T.SERVICE_ACCOUNT_ID, c.serviceAccountId());
        row.put(T.CLIENT_ID, c.clientId());
        row.put(T.CLIENT_IDENTIFIER, c.clientIdentifier());
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, c.id())
                .set(T.CREATED_AT, utc(c.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(Connection c, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(c.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Connection toEntity(MsgConnectionsRecord row) {
        return new Connection(
                row.getId(),
                row.getCode(),
                row.getApplicationCode(),
                row.getName(),
                row.getDescription(),
                row.getExternalId(),
                status(row.getId(), row.getStatus()),
                source(row.getId(), row.getSource()),
                row.getServiceAccountId(),
                row.getClientId(),
                row.getClientIdentifier(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /// [ConnectionStatus#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06).
    private static ConnectionStatus status(String rowId, String stored) {
        try {
            return ConnectionStatus.parse(stored);
        } catch (ConnectionStatus.UnrecognisedConnectionStatusException e) {
            throw new CorruptConnectionException(rowId, e);
        }
    }

    /// [ConnectionSource#parse], wrapped so a corrupt stored value fails
    /// loudly with the offending row's id (X-06).
    private static ConnectionSource source(String rowId, String stored) {
        try {
            return ConnectionSource.parse(stored);
        } catch (ConnectionSource.UnrecognisedConnectionSourceException e) {
            throw new CorruptConnectionException(rowId, e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

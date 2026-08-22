package io.flowcatalyst.platform.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.flowcatalyst.db.generated.tables.TntClients;
import io.flowcatalyst.db.generated.tables.records.TntClientsRecord;
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

import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;

/// `tnt_clients` via jOOQ. Notes live as a JSONB array on the row, so a
/// client is one row in and one row out; writes happen only on the unit of
/// work's transaction ([Persist]). Pure CRUD — no domain decisions live here.
public final class ClientRepository implements Persist<Client> {

    private static final TntClients T = TNT_CLIENTS;

    /// Upper bound of a search result (spec §3).
    public static final int SEARCH_LIMIT = 50;

    private static final TypeReference<List<ClientNote>> NOTES = new TypeReference<>() {
    };

    /// Reads: jOOQ acquires and releases a pooled connection per query.
    private final DSLContext dsl;

    public ClientRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<Client> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    /// Exact match on the stored (normalised) identifier.
    public Optional<Client> findByIdentifier(String identifier) {
        return findOne(T.IDENTIFIER.eq(identifier));
    }

    /// Every client, by identifier.
    public List<Client> findAll() {
        return findMany(DSL.noCondition());
    }

    /// Case-insensitive *contains* match on name or identifier, by
    /// identifier, at most [#SEARCH_LIMIT] rows (spec §3). A `null` or
    /// empty term matches everything.
    public List<Client> search(String term) {
        String pattern = "%" + (term == null ? "" : term) + "%";
        return List.copyOf(dsl.selectFrom(T)
                .where(T.NAME.likeIgnoreCase(pattern).or(T.IDENTIFIER.likeIgnoreCase(pattern)))
                .orderBy(T.IDENTIFIER.asc())
                .limit(SEARCH_LIMIT)
                .fetch()
                .map(ClientRepository::toEntity));
    }

    private Optional<Client> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(ClientRepository::toEntity);
    }

    private List<Client> findMany(Condition where) {
        return List.copyOf(dsl.selectFrom(T).where(where).orderBy(T.IDENTIFIER.asc()).fetch().map(ClientRepository::toEntity));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upserts the row `ON CONFLICT (id)`. `created_at` is written once and
    /// never updated; `updated_at` is stamped `now()` here, not taken from
    /// the aggregate (spec §8).
    @Override
    public void persist(Client c, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);

        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.NAME, c.name());
        row.put(T.IDENTIFIER, c.identifier());
        row.put(T.STATUS, c.status().name());
        row.put(T.STATUS_REASON, c.statusReason());
        row.put(T.STATUS_CHANGED_AT, utc(c.statusChangedAt()));
        row.put(T.NOTES, JSONB.jsonb(Json.write(c.notes())));
        row.put(T.UPDATED_AT, utc(Instant.now()));
        txDsl.insertInto(T)
                .set(T.ID, c.id())
                .set(T.CREATED_AT, utc(c.createdAt()))
                .set(row)
                .onConflict(T.ID).doUpdate().set(row)
                .execute();
    }

    /// Removes the row only — rows elsewhere referencing the client are untouched (spec §3).
    @Override
    public void delete(Client c, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(c.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static Client toEntity(TntClientsRecord row) {
        return new Client(
                row.getId(),
                row.getName(),
                row.getIdentifier(),
                ClientStatus.parse(row.getStatus()),
                row.getStatusReason(),
                instant(row.getStatusChangedAt()),
                notesOf(row.getNotes()),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    /// `NULL` / empty JSONB → no notes (spec §8).
    private static List<ClientNote> notesOf(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) return List.of();
        try {
            return Json.MAPPER.readValue(jsonb.data(), NOTES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tnt_clients.notes is not a valid note array", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}

package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnHosts;
import io.flowcatalyst.db.generated.tables.records.FnHostsRecord;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.FN_HOSTS;

/// `fn_hosts` via jOOQ (spec `function-registry.md` §6.3). `loaded` is a
/// foreign JSON shape (`CONVENTIONS.md` §8) written by the host process
/// itself: [#toEntity] drops any entry it cannot read rather than failing
/// the whole row (spec §6.3, §8 M14).
public final class FunctionHostRepository implements Persist<FunctionHost> {

    private static final FnHosts T = FN_HOSTS;

    private final DSLContext dsl;

    public FunctionHostRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<FunctionHost> findById(String id) {
        return dsl.selectFrom(T).where(T.ID.eq(id)).fetchOptional().map(FunctionHostRepository::toEntity);
    }

    public List<FunctionHost> listByPool(DnsLabel pool) {
        Objects.requireNonNull(pool, "pool");
        return List.copyOf(dsl.selectFrom(T).where(T.POOL.eq(pool.value())).orderBy(T.ID.asc())
                .fetch().map(FunctionHostRepository::toEntity));
    }

    /// Hosts of `pool` heartbeated at or after `seenSince`.
    public List<FunctionHost> listLive(DnsLabel pool, Instant seenSince) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(seenSince, "seenSince");
        return List.copyOf(dsl.selectFrom(T)
                .where(T.POOL.eq(pool.value())).and(T.LAST_HEARTBEAT.ge(utc(seenSince)))
                .orderBy(T.ID.asc())
                .fetch().map(FunctionHostRepository::toEntity));
    }

    /// One pool with its count of hosts seen since the query's cut-off.
    public record PoolSummary(DnsLabel pool, int hosts) {
    }

    /// Every pool with at least one host heartbeated at or after `seenSince`,
    /// with its live host count.
    public List<PoolSummary> pools(Instant seenSince) {
        Objects.requireNonNull(seenSince, "seenSince");
        return dsl.select(T.POOL, DSL.count())
                .from(T)
                .where(T.LAST_HEARTBEAT.ge(utc(seenSince)))
                .groupBy(T.POOL)
                .orderBy(T.POOL.asc())
                .fetch(r -> new PoolSummary(new DnsLabel(r.value1()), r.value2()));
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Upsert by id. `pool` and `started_at` are absent from the `SET` list
    /// — neither changes after [FunctionHost#register] (spec §6.3).
    @Override
    public void persist(FunctionHost h, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        JSONB loaded = JSONB.jsonb(Json.write(loadedToJson(h.loaded())));
        txDsl.insertInto(T)
                .set(T.ID, h.id())
                .set(T.POOL, h.pool().value())
                .set(T.STATE, h.state().name())
                .set(T.LOADED, loaded)
                .set(T.STARTED_AT, utc(h.startedAt()))
                .set(T.LAST_HEARTBEAT, utc(h.lastHeartbeat()))
                .onConflict(T.ID).doUpdate()
                .set(T.STATE, h.state().name())
                .set(T.LOADED, loaded)
                .set(T.LAST_HEARTBEAT, utc(h.lastHeartbeat()))
                .execute();
    }

    @Override
    public void delete(FunctionHost h, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.ID.eq(h.id())).execute();
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static FunctionHost toEntity(FnHostsRecord row) {
        return new FunctionHost(
                row.getId(),
                new DnsLabel(row.getPool()),
                FunctionHost.HostState.parse(row.getState()),
                readLoaded(row.getLoaded()),
                row.getStartedAt().toInstant(),
                row.getLastHeartbeat().toInstant());
    }

    // ── loaded JSON (foreign shape, spec §6.3, §8 M14) ──────────────────────

    private static JsonNode loadedToJson(List<FunctionHost.LoadedVersion> loaded) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        for (FunctionHost.LoadedVersion lv : loaded) {
            ObjectNode node = Json.MAPPER.createObjectNode();
            node.put("address", lv.address().render());
            node.put("version", lv.version());
            node.put("state", stateName(lv.state()));
            if (lv.state() instanceof FunctionHost.LoadState.Failed(String error)) {
                node.put("error", error);
            }
            array.add(node);
        }
        return array;
    }

    private static String stateName(FunctionHost.LoadState state) {
        return switch (state) {
            case FunctionHost.LoadState.Registered ignored -> "REGISTERED";
            case FunctionHost.LoadState.Loaded ignored -> "LOADED";
            case FunctionHost.LoadState.Failed ignored -> "FAILED";
        };
    }

    /// Tolerant reader: an entry with a bad address, an unreadable version or
    /// an unknown state is dropped rather than failing the whole row (spec
    /// §6.3, §8 M14). A non-array / `NULL` column reads as no loaded versions.
    private static List<FunctionHost.LoadedVersion> readLoaded(JSONB jsonb) {
        JsonNode root = fromJsonb(jsonb);
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<FunctionHost.LoadedVersion> result = new ArrayList<>();
        for (JsonNode entry : root) {
            readLoadedVersion(entry).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private static Optional<FunctionHost.LoadedVersion> readLoadedVersion(JsonNode node) {
        if (!node.isObject()) {
            return Optional.empty();
        }
        JsonNode addressNode = node.path("address");
        if (!addressNode.isString()) {
            return Optional.empty();
        }
        FunctionAddress address;
        try {
            address = FunctionAddress.parse(addressNode.asString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        JsonNode versionNode = node.path("version");
        if (!versionNode.isIntegralNumber() || !versionNode.canConvertToInt() || versionNode.asInt() <= 0) {
            return Optional.empty();
        }
        JsonNode stateNode = node.path("state");
        FunctionHost.LoadState state = stateNode.isString() ? readLoadState(stateNode.asString(), node) : null;
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(new FunctionHost.LoadedVersion(address, versionNode.asInt(), state));
    }

    private static FunctionHost.LoadState readLoadState(String raw, JsonNode node) {
        return switch (raw) {
            case "REGISTERED" -> new FunctionHost.LoadState.Registered();
            case "LOADED" -> new FunctionHost.LoadState.Loaded();
            case "FAILED" -> new FunctionHost.LoadState.Failed(
                    node.path("error").isString() ? node.path("error").asString() : "");
            default -> null;
        };
    }

    private static JsonNode fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(jsonb.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("fn_hosts.loaded is not valid JSON", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

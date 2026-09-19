package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnClientPolicies;
import io.flowcatalyst.db.generated.tables.records.FnClientPoliciesRecord;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.DSLContext;
import org.jooq.Field;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static io.flowcatalyst.db.generated.Tables.FN_CLIENT_POLICIES;

/// `fn_client_policies` via jOOQ (spec `function-registry.md` §6.4). Natural
/// key — no TSID; upsert by `client_id`. `signers` is a foreign JSON shape
/// (`CONVENTIONS.md` §8): [#readSigners] drops what it cannot read rather
/// than failing the whole row (spec §6.4, §8 M14).
///
/// [#PLATFORM_KEY] is the one place in the codebase that spells the reserved
/// primary-key value for the platform's own policy (ruling R2) — [#storedKey]
/// and [#ownerOf] are its only two uses, for write and read respectively
/// (spec §6.4, §8 M19).
public final class ClientPolicyRepository implements Persist<ClientPolicy> {

    private static final FnClientPolicies T = FN_CLIENT_POLICIES;

    /// No TSID is ever spelled this way (`EntityType` generates lower-case
    /// prefixes), so a client policy row can never collide with it.
    private static final String PLATFORM_KEY = "PLATFORM";

    private final DSLContext dsl;

    public ClientPolicyRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    public Optional<ClientPolicy> findByOwner(FunctionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return dsl.selectFrom(T).where(T.CLIENT_ID.eq(storedKey(owner))).fetchOptional()
                .map(ClientPolicyRepository::toEntity);
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    @Override
    public void persist(ClientPolicy p, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        var row = new LinkedHashMap<Field<?>, Object>();
        row.put(T.SIGNERS, JSONB.jsonb(Json.write(signersToJson(p.signers()))));
        row.put(T.MAX_DURATION_MS, p.maxDurationMs());
        row.put(T.MAX_CONCURRENCY, p.maxConcurrency());
        row.put(T.MAX_WASM_MEMORY_MB, p.maxWasmMemoryMb());
        row.put(T.MAX_DB_POOL_SIZE, p.maxDbPoolSize());
        row.put(T.UPDATED_AT, utc(p.updatedAt()));
        txDsl.insertInto(T)
                .set(T.CLIENT_ID, storedKey(p.owner()))
                .set(T.CREATED_AT, utc(p.createdAt()))
                .set(row)
                .onConflict(T.CLIENT_ID).doUpdate().set(row)
                .execute();
    }

    @Override
    public void delete(ClientPolicy p, DbTx tx) {
        DSL.using(tx.connection(), SQLDialect.POSTGRES).deleteFrom(T).where(T.CLIENT_ID.eq(storedKey(p.owner()))).execute();
    }

    // ── owner ⇄ stored key (spec §6.4, §8 M19) ──────────────────────────────

    private static String storedKey(FunctionOwner owner) {
        return switch (owner) {
            case FunctionOwner.Platform ignored -> PLATFORM_KEY;
            case FunctionOwner.Client(String clientId) -> clientId;
        };
    }

    private static FunctionOwner ownerOf(String clientId) {
        return PLATFORM_KEY.equals(clientId) ? new FunctionOwner.Platform() : FunctionOwner.ofClientId(clientId);
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    private static ClientPolicy toEntity(FnClientPoliciesRecord row) {
        return new ClientPolicy(
                ownerOf(row.getClientId()),
                readSigners(row.getSigners()),
                row.getMaxDurationMs(),
                row.getMaxConcurrency(),
                row.getMaxWasmMemoryMb(),
                row.getMaxDbPoolSize(),
                row.getCreatedAt().toInstant(),
                row.getUpdatedAt().toInstant());
    }

    // ── signers JSON (foreign shape, spec §6.4, §8 M14) ─────────────────────

    private static JsonNode signersToJson(List<ClientPolicy.SignerRule> signers) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        for (ClientPolicy.SignerRule rule : signers) {
            ObjectNode node = Json.MAPPER.createObjectNode();
            node.put("issuer", rule.issuer());
            node.put("subject", rule.subject());
            ArrayNode runtimes = node.putArray("runtimes");
            rule.runtimes().forEach(r -> runtimes.add(r.name()));
            array.add(node);
        }
        return array;
    }

    /// Tolerant reader (spec §6.4, §8 M14): a rule with a blank issuer or
    /// subject is dropped entirely; an unknown runtime name is dropped from
    /// the rule's runtime set, not the whole rule; a non-array / `NULL`
    /// column reads as no signers.
    private static List<ClientPolicy.SignerRule> readSigners(JSONB jsonb) {
        JsonNode root = fromJsonb(jsonb);
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<ClientPolicy.SignerRule> rules = new ArrayList<>();
        for (JsonNode entry : root) {
            readSignerRule(entry).ifPresent(rules::add);
        }
        return List.copyOf(rules);
    }

    private static Optional<ClientPolicy.SignerRule> readSignerRule(JsonNode node) {
        if (!node.isObject()) {
            return Optional.empty();
        }
        JsonNode issuerNode = node.path("issuer");
        JsonNode subjectNode = node.path("subject");
        if (!issuerNode.isString() || issuerNode.asString().isBlank()) {
            return Optional.empty();
        }
        if (!subjectNode.isString() || subjectNode.asString().isBlank()) {
            return Optional.empty();
        }
        Set<Runtime> runtimes = EnumSet.noneOf(Runtime.class);
        JsonNode runtimesNode = node.path("runtimes");
        if (runtimesNode.isArray()) {
            for (JsonNode r : runtimesNode) {
                if (!r.isString()) {
                    continue;
                }
                try {
                    runtimes.add(Runtime.parse(r.asString()));
                } catch (IllegalArgumentException ignored) {
                    // an unknown runtime is dropped, not fatal to the rule (spec §8 M14)
                }
            }
        }
        return Optional.of(new ClientPolicy.SignerRule(issuerNode.asString(), subjectNode.asString(), runtimes));
    }

    private static JsonNode fromJsonb(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(jsonb.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("fn_client_policies.signers is not valid JSON", e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

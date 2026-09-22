package io.flowcatalyst.platform.function;

import io.flowcatalyst.db.generated.tables.FnAliases;
import io.flowcatalyst.db.generated.tables.FnVersions;
import io.flowcatalyst.db.generated.tables.records.FnVersionsRecord;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Persist;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.flowcatalyst.db.generated.Tables.FN_ALIASES;
import static io.flowcatalyst.db.generated.Tables.FN_FUNCTIONS;
import static io.flowcatalyst.db.generated.Tables.FN_VERSIONS;

/// `fn_versions` via jOOQ (spec `function-registry.md` §6.2). A version's
/// content is immutable once published — enforced entirely by
/// [#persist]'s narrow `SET` list, never by application-level checks (§8 M9).
public final class FunctionVersionRepository implements Persist<FunctionVersion> {

    private static final Logger LOG = LoggerFactory.getLogger(FunctionVersionRepository.class);

    private static final FnVersions T = FN_VERSIONS;

    private final DSLContext dsl;

    public FunctionVersionRepository(DataSource dataSource) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    public Optional<FunctionVersion> findById(String id) {
        return findOne(T.ID.eq(id));
    }

    public Optional<FunctionVersion> findByFunctionAndVersion(String functionId, int version) {
        return findOne(T.FUNCTION_ID.eq(functionId).and(T.VERSION.eq(version)));
    }

    public Optional<FunctionVersion> findByFunctionAndDigest(String functionId, Digest digest) {
        Objects.requireNonNull(digest, "digest");
        return findOne(T.FUNCTION_ID.eq(functionId).and(T.DIGEST.eq(digest.value())));
    }

    /// Newest first (spec §6.2). Scoped to ONE function, so a corrupt row here throws
    /// [CorruptFunctionVersionException] like the single-row readers (§8: no unrelated
    /// function or pool is at risk from failing this one function's own version list).
    public List<FunctionVersion> listByFunction(String functionId) {
        return List.copyOf(dsl.selectFrom(T).where(T.FUNCTION_ID.eq(functionId))
                .orderBy(T.VERSION.desc()).fetch().map(FunctionVersionRepository::toEntityOrThrow));
    }

    /// The newest NON-RETIRED version for `functionId` (spec `function-context.md`
    /// §1, S1): the highest `version` whose state is `PUBLISHED` or `READY`.
    /// Since a function's `live` version can never be retired
    /// ([io.flowcatalyst.platform.function.operations.RetireVersion]'s own
    /// guard), this always resolves to (at least) the live version when one
    /// is set — the live version itself once nothing newer is PUBLISHED/READY.
    /// Scoped to ONE function, so a corrupt row here throws
    /// [CorruptFunctionVersionException] like the other single-row readers.
    public Optional<FunctionVersion> findNewestNonRetired(String functionId) {
        Objects.requireNonNull(functionId, "functionId");
        return dsl.selectFrom(T).where(T.FUNCTION_ID.eq(functionId)).and(T.STATE.ne("RETIRED"))
                .orderBy(T.VERSION.desc()).limit(1).fetchOptional()
                .map(FunctionVersionRepository::toEntityOrThrow);
    }

    /// The desired-state batch read: every version named by `ids`, one query. An id
    /// with no matching row is simply absent from [VersionBatch#versions]. A row whose
    /// manifest [Manifest#readStored] refuses is reported in [VersionBatch#corrupt]
    /// instead of throwing — see [CorruptFunctionVersionException]'s javadoc for why
    /// this batch reader deliberately departs from the general "a corrupt row fails the
    /// whole list" policy.
    public VersionBatch findByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return VersionBatch.EMPTY;
        }
        Map<String, FunctionVersion> byId = new HashMap<>();
        List<CorruptVersion> corrupt = new ArrayList<>();
        dsl.selectFrom(T).where(T.ID.in(ids)).forEach(row -> readRow(row, row.getId(), byId::put, corrupt::add));
        return new VersionBatch(byId, corrupt);
    }

    /// The desired-state candidate read (spec `function-api.md` §6.1, R3):
    /// the newest `PUBLISHED`-state version per function — one query, one
    /// hydration path (`CONVENTIONS.md` §8). Ordered `function_id`, `version
    /// desc` so the first row seen per function is its highest-numbered
    /// `PUBLISHED` row; a function with none is simply absent from
    /// [VersionBatch#versions]. When that highest-numbered row's manifest is
    /// corrupt, it is reported in [VersionBatch#corrupt] instead — never
    /// silently replaced by an OLDER `PUBLISHED` row of the same function,
    /// which would misreport what "newest" actually is.
    public VersionBatch newestPublishedByFunctions(Collection<String> functionIds) {
        Objects.requireNonNull(functionIds, "functionIds");
        if (functionIds.isEmpty()) {
            return VersionBatch.EMPTY;
        }
        Map<String, FunctionVersion> newestByFunction = new HashMap<>();
        List<CorruptVersion> corrupt = new ArrayList<>();
        Set<String> decided = new HashSet<>();
        dsl.selectFrom(T)
                .where(T.FUNCTION_ID.in(functionIds).and(T.STATE.eq("PUBLISHED")))
                .orderBy(T.FUNCTION_ID.asc(), T.VERSION.desc())
                .forEach(row -> {
                    if (decided.add(row.getFunctionId())) {
                        readRow(row, row.getFunctionId(), newestByFunction::put, corrupt::add);
                    }
                });
        return new VersionBatch(newestByFunction, corrupt);
    }

    /// Reads one row for a BATCH caller: on success, `onOk` gets `(key, entity)` — `key`
    /// is the caller's own map key ([#findByIds]: the version id; [#newestPublishedByFunctions]:
    /// the function id), NOT necessarily the row's own id. On a corrupt manifest (bad
    /// JSON, or [Manifest#readStored] refusing `runtime`/`entrypoint`), `onCorrupt` gets
    /// a [CorruptVersion] (always keyed by the row's OWN id/functionId, regardless of
    /// `key`) instead of the row failing the whole batch. [CorruptVersion#pool] is
    /// peeked independently of the failing fields when the manifest at least parsed as
    /// JSON ([Manifest#peekStoredPool]); `null` only when the `manifest` column itself
    /// is not valid JSON.
    private static void readRow(FnVersionsRecord row, String key, BiConsumer<String, FunctionVersion> onOk,
            Consumer<CorruptVersion> onCorrupt) {
        JsonNode manifestJson;
        try {
            manifestJson = Json.MAPPER.readTree(row.getManifest().data());
        } catch (JacksonException e) {
            onCorrupt.accept(new CorruptVersion(row.getId(), row.getFunctionId(), null,
                    new IllegalStateException("fn_versions.manifest is not valid JSON", e)));
            return;
        }
        try {
            onOk.accept(key, toEntity(row, manifestJson));
        } catch (IllegalStateException e) {
            onCorrupt.accept(new CorruptVersion(row.getId(), row.getFunctionId(), Manifest.peekStoredPool(manifestJson), e));
        }
    }

    /// [#findByIds] / [#newestPublishedByFunctions]'s result. A caller that must react
    /// to a corrupt row (`DesiredState#build`) reads [#corrupt]; one that doesn't (a
    /// plain function listing) can ignore it — that row is simply absent from
    /// [#versions], same as an id naming no row at all.
    public record VersionBatch(Map<String, FunctionVersion> versions, List<CorruptVersion> corrupt) {
        public VersionBatch {
            versions = Map.copyOf(versions);
            corrupt = List.copyOf(corrupt);
        }

        static final VersionBatch EMPTY = new VersionBatch(Map.of(), List.of());
    }

    /// A batch-read row [Manifest#readStored] refused — everything the caller can know
    /// WITHOUT the manifest. `pool` is a best-effort peek ([Manifest#peekStoredPool]),
    /// `null` only when `manifest` was not even valid JSON. `cause`'s message never
    /// contains manifest content ([Manifest#readStored]'s own messages are fixed
    /// strings; the JSON-parse failure's message is the only exception and is a Jackson
    /// syntax-error message, not a value from the document).
    public record CorruptVersion(String versionId, String functionId, DnsLabel pool, IllegalStateException cause) {
    }

    /// The publish-time warm-capacity read (spec `function-invocation.md`
    /// §4, §10 V1 `WARM_CAPACITY_EXCEEDED`): how many functions **other
    /// than** `excludingFunctionId`'s CURRENT `live` version has
    /// `manifest.warm() == true` and names `pool` — one join, decoded in
    /// Java since `warm`/`pool` live inside the stored JSONB manifest, not
    /// their own columns. `excludingFunctionId` is the function being
    /// published: its own live warm version is about to be replaced by the
    /// version under validation, so counting it here would stop a function
    /// already at the cap from ever republishing (spec §4). `PublishVersion`'s
    /// own validation adds the version being published, if it too is warm,
    /// on top of this count (spec: "live warm versions of other functions in
    /// that pool + this one").
    ///
    /// Same blast-radius concern as [#findByIds] (a corrupt `fn_versions` row must never
    /// take down a read about OTHER functions/versions): a live version whose manifest
    /// [Manifest#readStored] refuses is skipped — undercounting the warm cap by one is
    /// the safe direction (worst case: one extra warm function briefly over a soft cap),
    /// unlike throwing here, which would block EVERY publish platform-wide.
    public int countLiveWarmInPool(DnsLabel pool, String excludingFunctionId) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(excludingFunctionId, "excludingFunctionId");
        FnAliases a = FN_ALIASES;
        int count = 0;
        for (var row : dsl.select(T.ID, T.FUNCTION_ID, T.MANIFEST).from(T)
                .join(a).on(a.VERSION_ID.eq(T.ID))
                .where(a.ALIAS.eq(Function.LIVE))
                .and(T.FUNCTION_ID.ne(excludingFunctionId))
                .fetch()) {
            Manifest manifest;
            try {
                manifest = Manifest.readStored(readManifestJson(row.value3()));
            } catch (IllegalStateException e) {
                LOG.atError().setMessage("fn_versions row has an unreadable manifest; excluded from the warm-capacity count")
                        .addKeyValue("functionId", row.value2())
                        .addKeyValue("versionId", row.value1())
                        .log();
                continue;
            }
            if (manifest.warm() && manifest.pool().equals(pool)) {
                count++;
            }
        }
        return count;
    }

    private static JsonNode readManifestJson(JSONB manifest) {
        try {
            return Json.MAPPER.readTree(manifest.data());
        } catch (JacksonException e) {
            throw new IllegalStateException("fn_versions.manifest is not valid JSON", e);
        }
    }

    /// A SINGLE-row read: unlike the batch readers, a corrupt manifest here fails with
    /// [CorruptFunctionVersionException] naming the row id — this read is about THAT one
    /// version, so there is no unrelated function/pool for it to take down.
    private Optional<FunctionVersion> findOne(Condition where) {
        return dsl.selectFrom(T).where(where).fetchOptional().map(FunctionVersionRepository::toEntityOrThrow);
    }

    /// `SELECT … FOR UPDATE` on the version row, inside the caller's open
    /// transaction, hydrated through the same [#toEntity] path every other
    /// read uses — one hydration path (`CONVENTIONS.md` §8). Review fix,
    /// slice B3: `MarkVersionReady` locks the row here and guards `Published`
    /// on THIS read, so two heartbeats racing to mark the same version ready
    /// serialise — the second sees the first's committed `Ready` state
    /// instead of the pre-lock snapshot both would otherwise have passed.
    public Optional<FunctionVersion> lockById(String id, DbTx tx) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tx, "tx");
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        return txDsl.selectFrom(T).where(T.ID.eq(id)).forUpdate().fetchOptional()
                .map(FunctionVersionRepository::toEntityOrThrow);
    }

    // ── nextVersion (spec §6.2, §8 M10) ─────────────────────────────────────

    /// `SELECT … FROM fn_functions WHERE id = ? FOR UPDATE` on the caller's
    /// open transaction, then `max(version) + 1` (1 for the first). The lock
    /// is the point: two concurrent publishes must serialise to 1 and 2, not
    /// race to 1 and a unique-violation 500.
    ///
    /// @throws UseCaseException notFound `Function_NOT_FOUND` when `functionId` names no function
    public int nextVersion(String functionId, DbTx tx) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(tx, "tx");
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        boolean exists = txDsl.select(FN_FUNCTIONS.ID).from(FN_FUNCTIONS)
                .where(FN_FUNCTIONS.ID.eq(functionId))
                .forUpdate()
                .fetchOptional()
                .isPresent();
        if (!exists) {
            throw UseCaseException.resourceNotFound("Function", functionId);
        }
        Integer max = txDsl.select(DSL.max(T.VERSION)).from(T).where(T.FUNCTION_ID.eq(functionId))
                .fetchOne(Record1::value1);
        return (max == null ? 0 : max) + 1;
    }

    // ── Writes (inside the unit of work's transaction only) ────────────────

    /// Insert; on conflict by id the `SET` list is `state`, `ready_at`,
    /// `retired_at` only — every other column is write-once (spec §6.2, §8
    /// M9). `ready_at` / `retired_at` are written on the *update* side only
    /// when the new state actually carries them (`Ready` / `Retired`); a
    /// `Retired` update otherwise self-references the existing column so a
    /// version that was `Ready` before it was retired keeps that `ready_at`
    /// in the row (spec §6.2: "the record does not model it").
    @Override
    public void persist(FunctionVersion v, DbTx tx) {
        DSLContext txDsl = DSL.using(tx.connection(), SQLDialect.POSTGRES);
        String state = stateName(v.state());
        txDsl.insertInto(T)
                .set(T.ID, v.id())
                .set(T.FUNCTION_ID, v.functionId())
                .set(T.VERSION, v.version())
                .set(T.ARTIFACT_REF, v.artifactRef())
                .set(T.DIGEST, v.digest().value())
                .set(T.SIGNATURE_BUNDLE, v.signatureBundle())
                .set(T.SIGNATURE_BUNDLE_REF, v.signatureBundleRef())
                .set(T.SIGNER_ISSUER, v.signer() == null ? null : v.signer().issuer())
                .set(T.SIGNER_SUBJECT, v.signer() == null ? null : v.signer().subject())
                .set(T.MANIFEST, JSONB.jsonb(Json.write(v.manifest().toJson())))
                .set(T.STATE, state)
                .set(T.PUBLISHED_BY, v.publishedBy())
                .set(T.PUBLISHED_AT, utc(v.publishedAt()))
                .set(T.READY_AT, insertReadyAt(v.state()))
                .set(T.RETIRED_AT, insertRetiredAt(v.state()))
                .onConflict(T.ID).doUpdate()
                .set(T.STATE, state)
                .set(T.READY_AT, updateReadyAt(v.state()))
                .set(T.RETIRED_AT, updateRetiredAt(v.state()))
                .execute();
    }

    /// Versions are retired, never deleted (spec §6.2).
    @Override
    public void delete(FunctionVersion v, DbTx tx) {
        throw new UnsupportedOperationException("function versions are retired, never deleted");
    }

    // ── Row ↔ entity ───────────────────────────────────────────────────────

    /// The single-row readers' path: parses the manifest itself, wrapping BOTH a
    /// JSON-syntax failure and [#toEntity]'s own `IllegalStateException` (unreadable
    /// `runtime`/`entrypoint`, or an unrecognised `state`) into
    /// [CorruptFunctionVersionException] naming this row — the batch readers use
    /// [#readRow] instead, which never throws.
    private static FunctionVersion toEntityOrThrow(FnVersionsRecord row) {
        JsonNode manifestJson;
        try {
            manifestJson = Json.MAPPER.readTree(row.getManifest().data());
        } catch (JacksonException e) {
            throw new CorruptFunctionVersionException(row.getId(),
                    new IllegalStateException("fn_versions.manifest is not valid JSON", e));
        }
        try {
            return toEntity(row, manifestJson);
        } catch (IllegalStateException e) {
            throw new CorruptFunctionVersionException(row.getId(), e);
        }
    }

    private static FunctionVersion toEntity(FnVersionsRecord row, JsonNode manifestJson) {
        SignerIdentity signer = row.getSignerIssuer() == null || row.getSignerSubject() == null
                ? null : new SignerIdentity(row.getSignerIssuer(), row.getSignerSubject());
        return new FunctionVersion(
                row.getId(),
                row.getFunctionId(),
                row.getVersion(),
                row.getArtifactRef(),
                new Digest(row.getDigest()),
                row.getSignatureBundle(),
                row.getSignatureBundleRef(),
                signer,
                Manifest.readStored(manifestJson),
                state(row),
                row.getPublishedBy(),
                row.getPublishedAt().toInstant());
    }

    private static FunctionVersion.VersionState state(FnVersionsRecord row) {
        return switch (row.getState()) {
            case "PUBLISHED" -> new FunctionVersion.VersionState.Published();
            case "READY" -> new FunctionVersion.VersionState.Ready(row.getReadyAt().toInstant());
            case "RETIRED" -> new FunctionVersion.VersionState.Retired(row.getRetiredAt().toInstant());
            default -> throw new IllegalStateException("unrecognised fn_versions.state: " + row.getState());
        };
    }

    private static String stateName(FunctionVersion.VersionState state) {
        return switch (state) {
            case FunctionVersion.VersionState.Published ignored -> "PUBLISHED";
            case FunctionVersion.VersionState.Ready ignored -> "READY";
            case FunctionVersion.VersionState.Retired ignored -> "RETIRED";
        };
    }

    private static OffsetDateTime insertReadyAt(FunctionVersion.VersionState state) {
        return state instanceof FunctionVersion.VersionState.Ready ready ? utc(ready.at()) : null;
    }

    private static OffsetDateTime insertRetiredAt(FunctionVersion.VersionState state) {
        return state instanceof FunctionVersion.VersionState.Retired retired ? utc(retired.at()) : null;
    }

    private static Field<OffsetDateTime> updateReadyAt(FunctionVersion.VersionState state) {
        return state instanceof FunctionVersion.VersionState.Ready ready ? DSL.val(utc(ready.at())) : T.READY_AT;
    }

    private static Field<OffsetDateTime> updateRetiredAt(FunctionVersion.VersionState state) {
        return state instanceof FunctionVersion.VersionState.Retired retired ? DSL.val(utc(retired.at())) : T.RETIRED_AT;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

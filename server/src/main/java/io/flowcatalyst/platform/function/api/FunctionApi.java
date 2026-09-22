package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionAddressPattern;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.SecretValue;
import io.flowcatalyst.platform.function.SettingKey;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.function.artifact.ArtifactBlobStore;
import io.flowcatalyst.platform.function.artifact.ArtifactException;
import io.flowcatalyst.platform.function.artifact.ArtifactHttpException;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.Access;
import io.flowcatalyst.platform.function.operations.CreateCommand;
import io.flowcatalyst.platform.function.operations.CreateFunction;
import io.flowcatalyst.platform.function.operations.DeleteCommand;
import io.flowcatalyst.platform.function.operations.DeleteFunction;
import io.flowcatalyst.platform.function.operations.DeleteFunctionSecret;
import io.flowcatalyst.platform.function.operations.DeleteSecretCommand;
import io.flowcatalyst.platform.function.operations.FunctionEvents;
import io.flowcatalyst.platform.function.operations.PromoteCommand;
import io.flowcatalyst.platform.function.operations.PromoteVersion;
import io.flowcatalyst.platform.function.operations.PublishCommand;
import io.flowcatalyst.platform.function.operations.PublishVersion;
import io.flowcatalyst.platform.function.operations.RetireCommand;
import io.flowcatalyst.platform.function.operations.RetireVersion;
import io.flowcatalyst.platform.function.operations.SetConfigCommand;
import io.flowcatalyst.platform.function.operations.SetFunctionConfig;
import io.flowcatalyst.platform.function.operations.SetFunctionSecret;
import io.flowcatalyst.platform.function.operations.SetSecretCommand;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.function.operations.UpdateCommand;
import io.flowcatalyst.platform.function.operations.UpdateFunction;
import io.flowcatalyst.platform.function.TriggerObject;
import io.flowcatalyst.platform.function.TriggerObjectKind;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.shared.apicommon.OffsetPage;
import io.flowcatalyst.platform.shared.apicommon.PageQuery;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_MANAGE;
import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_PROMOTE;
import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_PUBLISH;
import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_SECRET_MANAGE;
import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_VIEW;

/// The `/api/functions` surface (spec `function-api.md` §4.1, §4.2). Java-first —
/// outside the OpenAPI lockfile (spec §0); listed in `parity/surface.json`
/// instead. A write handler does exactly: coarse permission → command from
/// DTO → `Operation.run` → response. Reads go straight to the repository and
/// apply reach themselves (`CONVENTIONS.md` §2: "reads do not go through use
/// cases").
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/functions` | 200 [OffsetPage] of [FunctionResponse] |
/// | POST | `/api/functions` | 201 [FunctionResponse] |
/// | GET | `/api/functions/{address}` | 200 [FunctionResponse] |
/// | PUT | `/api/functions/{address}` | 204 |
/// | DELETE | `/api/functions/{address}` | 204 |
/// | GET | `/api/functions/{address}/status` | 200 [StatusResponse] |
/// | GET | `/api/function-pools` | 200 `[`[PoolSummaryResponse]`]` |
/// | POST | `/api/functions/{address}/versions` | 201 [PublishResponse] |
/// | GET | `/api/functions/{address}/versions` | 200 `[`[VersionResponse]`]` |
/// | GET | `/api/functions/{address}/versions/{version}` | 200 [VersionResponse] (+ `manifest`) |
/// | POST | `/api/functions/{address}/versions/{version}/retire` | 200 [VersionResponse] |
/// | PUT | `/api/functions/{address}/aliases/{alias}` | 200 [PromoteResponse] |
/// | GET | `/api/functions/{address}/aliases` | 200 `[`[AliasResponse]`]` |
///
/// `GET /api/functions/{address}/status` (spec §6.3, work package B2) is
/// gated exactly like every other by-address read: `FUNCTION_VIEW` + the
/// ordinary per-function reach (review fix, slice B3). Only `GET
/// /api/function-pools` is `requireAnchor` + `FUNCTION_VIEW` — pools are
/// cross-tenant infrastructure state with no owning function to reach.
public final class FunctionApi {

    private static final Logger LOG = LoggerFactory.getLogger(FunctionApi.class);

    private FunctionApi() {
    }

    /// The immutable fields a `PUT` body may never carry (spec §4.2), in the
    /// order they are checked — the first one present in the raw body names
    /// the `FUNCTION_IMMUTABLE_FIELD` error.
    private static final List<String> IMMUTABLE_FIELDS =
            List.of("serviceName", "name", "applicationCode", "clientId", "runtime");

    public record State(FunctionRepository repo, ApplicationRepository applications, ClientRepository clients,
                        UnitOfWork uow, FunctionVersionRepository versions, FunctionHostRepository hosts,
                        ClientPolicyRepository policies, FunctionLimits limits, Signatures signatures,
                        TriggerSync triggerSync, TriggerObjectRepository triggerObjects,
                        SubscriptionRepository subscriptions, DispatchPoolRepository dispatchPools,
                        ScheduledJobRepository scheduledJobs, FunctionSettingsRepository settings,
                        Optional<Encryption> encryption, Optional<ArtifactBlobStore> artifactBlobStore) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(versions, "versions");
            Objects.requireNonNull(hosts, "hosts");
            Objects.requireNonNull(policies, "policies");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(signatures, "signatures");
            Objects.requireNonNull(triggerSync, "triggerSync");
            Objects.requireNonNull(triggerObjects, "triggerObjects");
            Objects.requireNonNull(subscriptions, "subscriptions");
            Objects.requireNonNull(dispatchPools, "dispatchPools");
            Objects.requireNonNull(scheduledJobs, "scheduledJobs");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(encryption, "encryption");
            Objects.requireNonNull(artifactBlobStore, "artifactBlobStore");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/functions", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/functions", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/functions/{address}", Auth.scoped(ctx -> getOne(ctx, s)));
        write.put("/api/functions/{address}", Auth.scoped(ctx -> update(ctx, s)));
        write.delete("/api/functions/{address}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.get("/api/functions/{address}/status", Auth.scoped(ctx -> status(ctx, s)));
        routes.get("/api/function-pools", Auth.scoped(ctx -> pools(ctx, s)));
        // §5: versions and aliases (slice B3).
        write.post("/api/functions/{address}/versions", Auth.scoped(ctx -> publish(ctx, s)));
        routes.get("/api/functions/{address}/versions", Auth.scoped(ctx -> listVersions(ctx, s)));
        routes.get("/api/functions/{address}/versions/{version}", Auth.scoped(ctx -> getVersion(ctx, s)));
        write.post("/api/functions/{address}/versions/{version}/retire", Auth.scoped(ctx -> retire(ctx, s)));
        write.put("/api/functions/{address}/aliases/{alias}", Auth.scoped(ctx -> promote(ctx, s)));
        routes.get("/api/functions/{address}/aliases", Auth.scoped(ctx -> listAliases(ctx, s)));
        // spec `function-artifact-upload.md` §3: streamed, never buffered (Routes.putStreaming).
        // Deliberately NOT `write`: API_WRITE pins a connection for the whole request, and
        // this one spends its time reading a body, not in a transaction — its only database
        // work is the reach lookup. API_READ borrows per statement (spec §3).
        routes.putStreaming("/api/functions/{address}/artifacts/{digest}", Auth.scoped(ctx -> uploadArtifact(ctx, s)));
        // §1 (function-context.md, slice D4a): platform-stored config/secrets.
        routes.get("/api/functions/{address}/config", Auth.scoped(ctx -> getConfig(ctx, s)));
        write.put("/api/functions/{address}/config", Auth.scoped(ctx -> putConfig(ctx, s)));
        routes.get("/api/functions/{address}/secrets", Auth.scoped(ctx -> getSecrets(ctx, s)));
        write.put("/api/functions/{address}/secrets/{key}", Auth.scoped(ctx -> putSecret(ctx, s)));
        write.delete("/api/functions/{address}/secrets/{key}", Auth.scoped(ctx -> deleteSecret(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, FUNCTION_VIEW);
        PageQuery page = PageQuery.from(ctx);
        FunctionRepository.PageFilter filter = listFilter(ctx, ac);
        List<Function> rows = s.repo().findWithFilters(filter, page.pageSize(), (int) page.offset());
        long total = s.repo().countWithFilters(filter);
        // One batch read for every row's live version (spec §4.4), not one per row. A row
        // whose live version has a corrupt manifest is simply absent from the map (never
        // fails this list for every OTHER row) — see FunctionVersionRepository.VersionBatch.
        List<String> liveIds = rows.stream().map(Function::liveVersionId).flatMap(Optional::stream).toList();
        Map<String, FunctionVersion> liveVersions = s.versions().findByIds(liveIds).versions();
        ctx.json(OffsetPage.of(rows.stream()
                .map(f -> FunctionResponse.from(f, f.liveVersionId().map(liveVersions::get).orElse(null)))
                .toList(), page, total));
    }

    private static void getOne(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        ctx.json(FunctionResponse.from(f, liveVersionOf(s, f)));
    }

    private static void create(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_MANAGE);
        var req = ctx.bodyAsClass(CreateFunctionRequest.class);
        var event = CreateFunction.of(s.repo(), s.applications(), s.clients())
                .run(s.uow(), req.toCommand(), Auth.executionContext());
        Function f = s.repo().findById(event.functionId())
                .orElseThrow(() -> HttpError.internal("REPO", "function created but row not found", null));
        // A brand-new function has no version yet — never a batch read for one row.
        ctx.status(201).json(FunctionResponse.from(f, null));
    }

    /// spec §5.1: `POST /api/functions/{address}/versions`. `FUNCTION_PUBLISH`
    /// gates it (spec §2); reach + `FUNCTION_DISABLED` + everything else is
    /// `PublishVersion`'s own job.
    private static void publish(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_PUBLISH);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        var req = ctx.bodyAsClass(PublishRequest.class);
        PublishVersion.Result result = PublishVersion
                .of(s.repo(), s.versions(), s.policies(), s.limits(), s.signatures(), s.triggerSync(), s.artifactBlobStore())
                .run(s.uow(), req.toCommand(address), Auth.executionContext());
        ctx.status(201).json(PublishResponse.from(result.version()));
    }

    private static final int UPLOAD_CHUNK_BYTES = 64 * 1024;

    /// spec `function-artifact-upload.md` §3: `PUT
    /// /api/functions/{address}/artifacts/{digest}`, streamed
    /// (`Routes.putStreaming` — never buffered). The table's exact order:
    /// store configured, then the coarse `FUNCTION_PUBLISH` permission
    /// (`Authorised exactly as publish is`), then reach (404), then the
    /// `Content-Length` precheck — every one of those before a single byte
    /// of the body is read (spec's own ordering, U5).
    private static void uploadArtifact(Exchange ctx, State s) {
        ArtifactBlobStore store = s.artifactBlobStore().orElseThrow(ArtifactHttpException::storeNotConfigured);
        Checks.require(Auth.current(), FUNCTION_PUBLISH);
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        Digest digest = Digest.parse(ctx.pathParam("digest"));

        long declaredLength = ctx.contentLength();
        if (declaredLength >= 0 && declaredLength > ArtifactBlobStore.MAX_BYTES) {
            throw ArtifactHttpException.tooLarge(ArtifactBlobStore.MAX_BYTES);
        }

        Path temp;
        try {
            temp = Files.createTempFile("fc-artifact-upload-", ".tmp");
        } catch (IOException e) {
            throw new UncheckedIOException("creating the upload temp file", e);
        }
        try {
            long count = 0;
            Digest actual;
            try (InputStream body = ctx.bodyStream();
                 var out = Files.newOutputStream(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                MessageDigest sha256 = sha256();
                var digestIn = new DigestInputStream(body, sha256);
                byte[] buf = new byte[UPLOAD_CHUNK_BYTES];
                int n;
                while ((n = digestIn.read(buf)) != -1) {
                    count += n;
                    if (count > ArtifactBlobStore.MAX_BYTES) {
                        throw ArtifactHttpException.tooLarge(ArtifactBlobStore.MAX_BYTES);
                    }
                    out.write(buf, 0, n);
                }
                actual = new Digest("sha256:" + HexFormat.of().formatHex(sha256.digest()));
            } catch (IOException e) {
                throw new UncheckedIOException("reading the uploaded artifact", e);
            }
            if (count == 0) {
                throw ArtifactHttpException.empty();
            }
            if (!actual.equals(digest)) {
                throw ArtifactHttpException.digestMismatch(digest, actual);
            }
            try {
                // Idempotent (spec §3): uploading a digest that is already there is a
                // 200 with the same body — the bytes are still read and hashed above,
                // the route never answers for bytes it did not see.
                store.put(f.id(), digest, temp);
            } catch (ArtifactException e) {
                throw HttpError.internal("ARTIFACT_STORE_ERROR", "storing the uploaded artifact failed", e);
            }
            String hex = digest.value().substring("sha256:".length());
            ctx.status(200).json(new UploadArtifactResponse("platform://" + f.id() + "/" + hex, digest.value(), count));
        } finally {
            deleteQuietly(temp);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            // best-effort cleanup — a leftover temp file is harmless and never served
        }
    }

    /// spec §5.2: `GET /api/functions/{address}/versions`, newest first.
    private static void listVersions(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        String liveId = f.liveVersionId().orElse(null);
        List<VersionResponse> out = s.versions().listByFunction(f.id()).stream()
                .map(v -> VersionResponse.summary(v, v.id().equals(liveId)))
                .toList();
        ctx.json(out);
    }

    /// spec §5.2: `GET /api/functions/{address}/versions/{v}` — adds `manifest`.
    private static void getVersion(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        int version = parseVersionNumber(ctx.pathParam("version"));
        FunctionVersion v = versionOrNotFound(s, f, version);
        ctx.json(VersionResponse.detail(v, f.isLive(v.id())));
    }

    /// spec §5.2: `POST /api/functions/{address}/versions/{v}/retire`.
    /// `FUNCTION_PUBLISH` gates retire too (spec §2's table).
    private static void retire(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_PUBLISH);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        int version = parseVersionNumber(ctx.pathParam("version"));
        RetireVersion.of(s.repo(), s.versions())
                .run(s.uow(), new RetireCommand(address, version), Auth.executionContext());
        Function f = functionByAddress(s, address, Auth.current());
        FunctionVersion v = versionOrNotFound(s, f, version);
        ctx.json(VersionResponse.summary(v, f.isLive(v.id())));
    }

    /// spec §5.2: `PUT /api/functions/{address}/aliases/{alias}`. The `{alias}`
    /// segment is passed straight through to `Function.promote`, which is the
    /// ONE place `ALIAS_UNSUPPORTED` is decided (spec §6.1's own doc) — no
    /// duplicate check here, so the two paths can never disagree.
    private static void promote(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_PROMOTE);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        String alias = ctx.pathParam("alias");
        var req = ctx.bodyAsClass(PromoteRequest.class);
        FunctionEvents.AliasChanged event = PromoteVersion.of(s.repo(), s.versions(), s.triggerSync(), s.settings())
                .run(s.uow(), new PromoteCommand(address, alias, req.version()), Auth.executionContext());
        Integer previousVersion = event.previousVersionId() == null ? null
                : s.versions().findById(event.previousVersionId()).map(FunctionVersion::version).orElse(null);
        ctx.json(new PromoteResponse(event.alias(), event.version(), event.versionId(), previousVersion));
    }

    /// spec §5.2: `GET /api/functions/{address}/aliases`.
    private static void listAliases(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        List<AliasResponse> out = new ArrayList<>();
        for (Function.FunctionAlias a : f.aliases()) {
            s.versions().findById(a.versionId())
                    .ifPresent(v -> out.add(new AliasResponse(a.alias(), v.version(), a.versionId(), a.updatedBy(), a.updatedAt())));
        }
        ctx.json(out);
    }

    // ── §1 (function-context.md, D4a): platform-stored config/secrets ───────

    /// spec §1 (S1): `GET .../config` — `{values, declared, missing, declaredBy}`.
    /// `declared` is the ordered union of the live manifest's `config` keys
    /// and the `?version=` candidate's (absent ⇒ the newest non-retired
    /// version); `missing` is the subset of `declared` with no value set.
    private static void getConfig(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        Map<String, String> values = s.settings().configMap(f.id());
        Declared declared = declared(s, f, queryParam(ctx, "version"), Manifest::config);
        ctx.json(new ConfigResponse(new TreeMap<>(values), declared.keys(), missing(declared.keys(), values.keySet()),
                declared.declaredBy()));
    }

    /// spec §1 (S1): `PUT .../config` — full replacement; `SetFunctionConfig`
    /// owns the key-format/size validation. 200 with the GET shape, honouring
    /// the same optional `?version=` the GET route does.
    private static void putConfig(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_MANAGE);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        var req = ctx.bodyAsClass(SetConfigRequest.class);
        SetFunctionConfig.of(s.repo(), s.settings())
                .run(s.uow(), new SetConfigCommand(address, req.values()), Auth.executionContext());
        Function f = functionByAddress(s, address, Auth.current());
        Map<String, String> values = s.settings().configMap(f.id());
        Declared declared = declared(s, f, queryParam(ctx, "version"), Manifest::config);
        ctx.json(new ConfigResponse(new TreeMap<>(values), declared.keys(), missing(declared.keys(), values.keySet()),
                declared.declaredBy()));
    }

    /// spec §1 (S1): `GET .../secrets` — `{keys, declared, missing, declaredBy}`.
    /// **Never a value** — [FunctionSettingsRepository.SecretInfo] carries none.
    private static void getSecrets(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        if (!encryptionConfigured(ctx, s)) {
            return;
        }
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        List<FunctionSettingsRepository.SecretInfo> infos = s.settings().listSecrets(f.id());
        List<SecretKeyResponse> keys = infos.stream()
                .map(i -> new SecretKeyResponse(i.key(), i.updatedAt(), i.updatedBy())).toList();
        Declared declared = declared(s, f, queryParam(ctx, "version"), Manifest::secrets);
        Set<String> present = infos.stream().map(FunctionSettingsRepository.SecretInfo::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        ctx.json(new SecretListResponse(keys, declared.keys(), missing(declared.keys(), present), declared.declaredBy()));
    }

    /// spec §1: `PUT .../secrets/{key}` — `{value}`, 204. Never echoes the
    /// value back (X1): the response is empty.
    private static void putSecret(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_SECRET_MANAGE);
        if (!encryptionConfigured(ctx, s)) {
            return;
        }
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        String key = ctx.pathParam("key");
        var req = ctx.bodyAsClass(SetSecretRequest.class);
        SetFunctionSecret.of(s.repo(), s.settings())
                .run(s.uow(), new SetSecretCommand(address, key, new SecretValue(req.value() == null ? "" : req.value())),
                        Auth.executionContext());
        ctx.status(204);
    }

    /// spec §1: `DELETE .../secrets/{key}` — 204, 404 when absent.
    private static void deleteSecret(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_SECRET_MANAGE);
        if (!encryptionConfigured(ctx, s)) {
            return;
        }
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        String key = ctx.pathParam("key");
        DeleteFunctionSecret.of(s.repo(), s.settings())
                .run(s.uow(), new DeleteSecretCommand(address, key), Auth.executionContext());
        ctx.status(204);
    }

    /// spec §1: "`FLOWCATALYST_APP_KEY` unconfigured ⇒ the secret routes are
    /// `503 ENCRYPTION_UNCONFIGURED`" — written directly (not a
    /// [UseCaseException]: the platform's use-case kinds are 400/401/403/404/
    /// 409/500 only, `CONVENTIONS.md` §4) so this is the one place a function
    /// route answers 503.
    ///
    /// @return `true` when encryption IS configured and the handler should proceed
    private static boolean encryptionConfigured(Exchange ctx, State s) {
        if (s.encryption().isPresent()) {
            return true;
        }
        HttpError.write(ctx, 503, "ENCRYPTION_UNCONFIGURED",
                "FLOWCATALYST_APP_KEY is not configured; function secrets are unavailable", Map.of());
        return false;
    }

    /// `declared`/`declaredBy` for `.../config` and `.../secrets` (spec §1,
    /// S1). Absent `versionParam`: the candidate is the newest NON-RETIRED
    /// version ([FunctionVersionRepository#findNewestNonRetired]) — the live
    /// version itself once nothing newer is `PUBLISHED`/`READY`, since a
    /// live version is never retired. Present: the candidate is that EXACT
    /// version, `RETIRED` included ("its keys are historical but the caller
    /// asked") — [#versionOrNotFound] never filters by state. `declared` is
    /// live's keys first, then the candidate's not already listed;
    /// `declaredBy` names each manifest that contributed, live first, each
    /// with its OWN full key list (not just what it *added*) so the caller
    /// can say which version(s) want a given key.
    ///
    /// @throws UseCaseException notFound `FunctionVersion_NOT_FOUND` when `versionParam` names no version of `f`
    /// @throws UseCaseException validation `VERSION_INVALID` when `versionParam` is not a positive integer
    private static Declared declared(State s, Function f, String versionParam,
            java.util.function.Function<Manifest, List<String>> keysOf) {
        FunctionVersion live = liveVersionOf(s, f);
        FunctionVersion candidate = versionParam == null
                ? s.versions().findNewestNonRetired(f.id()).orElse(null)
                : versionOrNotFound(s, f, parseVersionNumber(versionParam));

        List<String> declared = new ArrayList<>();
        List<DeclaredByEntry> declaredBy = new ArrayList<>();
        if (live != null) {
            List<String> liveKeys = keysOf.apply(live.manifest());
            declared.addAll(liveKeys);
            declaredBy.add(new DeclaredByEntry(live.version(), liveKeys));
        }
        if (candidate != null && (live == null || !candidate.id().equals(live.id()))) {
            List<String> candidateKeys = keysOf.apply(candidate.manifest());
            for (String key : candidateKeys) {
                if (!declared.contains(key)) {
                    declared.add(key);
                }
            }
            declaredBy.add(new DeclaredByEntry(candidate.version(), candidateKeys));
        }
        return new Declared(List.copyOf(declared), List.copyOf(declaredBy));
    }

    /// [#declared]'s result: the union, and which manifest(s) contributed.
    private record Declared(List<String> keys, List<DeclaredByEntry> declaredBy) {
    }

    private static List<String> missing(List<String> declared, Set<String> present) {
        List<String> out = new ArrayList<>();
        for (String key : declared) {
            if (!present.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /// `FUNCTION_IMMUTABLE_FIELD` (spec §4.2) is checked against the RAW body
    /// tree, before `UpdateFunctionRequest` binding ever discards an unknown
    /// key silently — that is how someone would come to believe they had
    /// renamed a function (design §10.17).
    private static void update(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_MANAGE);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        rejectImmutableFields(ctx);
        var req = ctx.bodyAsClass(UpdateFunctionRequest.class);
        UpdateFunction.of(s.repo(), s.triggerSync()).run(s.uow(), req.toCommand(address), Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_MANAGE);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        FunctionEvents.FunctionDeleted event = DeleteFunction.of(s.repo(), s.triggerSync())
                .run(s.uow(), new DeleteCommand(address), Auth.executionContext());
        // spec `function-artifact-upload.md` §5 "Function delete" (R4): best-effort,
        // AFTER the transaction commits — a store failure is a WARN, never a failed
        // delete. An orphan blob is garbage, not state.
        s.artifactBlobStore().ifPresent(store -> {
            try {
                store.deleteAll(event.functionId());
            } catch (ArtifactException e) {
                LOG.atWarn().setMessage("deleting a deleted function's artifacts failed")
                        .addKeyValue("id", event.functionId())
                        .setCause(e)
                        .log();
            }
        });
        ctx.status(204);
    }

    /// spec §6.3: gated exactly like the other by-address reads —
    /// `FUNCTION_VIEW` + the ordinary per-function reach (review fix, slice
    /// B3: `requireAnchor` was wrong here; only `GET /api/function-pools`
    /// below is anchor-gated, since pools are cross-tenant infrastructure
    /// state with no owning function to reach). A function out of reach is
    /// 404, never 403 — same rule as [#getOne], sharing [Access#canReach] so
    /// the two paths can never disagree. [FunctionHostRepository#listAll] is
    /// the "hosts reporting this address" read: an in-memory filter over
    /// every host, any pool, since a function's versions can each name a
    /// different pool.
    private static void status(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        Function f = functionByAddress(s, address, Auth.current());

        List<FunctionVersion> versions = s.versions().listByFunction(f.id());
        List<StatusResponse.VersionSummary> versionSummaries = versions.stream()
                .map(v -> new StatusResponse.VersionSummary(v.version(), versionStateWire(v.state())))
                .toList();

        StatusResponse.Live live = f.liveVersionId()
                .flatMap(id -> versions.stream().filter(v -> v.id().equals(id)).findFirst())
                .map(v -> new StatusResponse.Live(v.version()))
                .orElse(null);

        Instant now = Instant.now();
        List<StatusResponse.HostSummary> hostSummaries = new ArrayList<>();
        for (FunctionHost h : s.hosts().listAll()) {
            List<StatusResponse.LoadedSummary> matching = h.loaded().stream()
                    .filter(lv -> lv.address().equals(address))
                    .map(lv -> new StatusResponse.LoadedSummary(lv.version(), loadStateWire(lv.state()), errorOf(lv.state())))
                    .toList();
            if (!matching.isEmpty()) {
                boolean stale = h.lastHeartbeat().isBefore(now.minus(FunctionHost.LIVE_WINDOW));
                hostSummaries.add(new StatusResponse.HostSummary(
                        h.id(), h.pool().value(), h.state().name(), h.lastHeartbeat(), stale, matching));
            }
        }
        hostSummaries.sort(Comparator.comparing(StatusResponse.HostSummary::hostId));

        List<StatusResponse.WiringEntry> wiring = s.triggerObjects().listByFunction(f.id()).stream()
                .map(o -> new StatusResponse.WiringEntry(o.kind().name(), o.triggerKey(), o.objectId(), wiringPresent(s, o)))
                .toList();

        ctx.json(new StatusResponse(address.render(), f.status().name(), live, versionSummaries, hostSummaries, wiring));
    }

    /// Whether the object a `fn_trigger_objects` row names still exists in
    /// its own table (spec `function-invocation.md` §4 D2 note): `false`
    /// when it was hand-deleted out from under the link row — the next
    /// promote recreates it (spec §10, "hand-deleted linked subscription is
    /// recreated at the next promote").
    private static boolean wiringPresent(State s, TriggerObject o) {
        return switch (o.kind()) {
            case POOL -> s.dispatchPools().findById(o.objectId()).isPresent();
            case SUBSCRIPTION -> s.subscriptions().findById(o.objectId()).isPresent();
            case SCHEDULED_JOB -> s.scheduledJobs().findById(o.objectId()).isPresent();
        };
    }

    /// spec §6.3: `requireAnchor` + `FUNCTION_VIEW`; counts reuse
    /// [FunctionHostRepository#pools], the same "seen since" cut-off
    /// [FunctionHost#LIVE_WINDOW] gives every other host-liveness read.
    private static void pools(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Instant seenSince = Instant.now().minus(FunctionHost.LIVE_WINDOW);
        List<PoolSummaryResponse> out = s.hosts().pools(seenSince).stream()
                .map(p -> new PoolSummaryResponse(p.pool().value(), p.hosts()))
                .toList();
        ctx.json(out);
    }

    private static String versionStateWire(FunctionVersion.VersionState state) {
        return switch (state) {
            case FunctionVersion.VersionState.Published ignored -> "PUBLISHED";
            case FunctionVersion.VersionState.Ready ignored -> "READY";
            case FunctionVersion.VersionState.Retired ignored -> "RETIRED";
        };
    }

    private static String loadStateWire(FunctionHost.LoadState state) {
        return switch (state) {
            case FunctionHost.LoadState.Registered ignored -> "REGISTERED";
            case FunctionHost.LoadState.Loaded ignored -> "LOADED";
            case FunctionHost.LoadState.Failed ignored -> "FAILED";
        };
    }

    private static String errorOf(FunctionHost.LoadState state) {
        return state instanceof FunctionHost.LoadState.Failed(String error) ? error : null;
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Load-or-404, out-of-reach-or-404 (spec §2: never 403, which would
    /// confirm the address) — shares [Access#canReach] with the write
    /// operations' [Access#requireReach] so the two paths can never
    /// disagree about what "out of reach" means.
    private static Function functionByAddress(State s, FunctionAddress address, AuthContext ac) {
        Function f = s.repo().findByAddress(address).orElseThrow(() -> HttpError.notFound("Function", address.render()));
        if (!Access.canReach(ac, f)) {
            throw HttpError.notFound("Function", address.render());
        }
        return f;
    }

    /// `{address}` is one path segment that may itself contain dots (spec
    /// §1) — `FunctionAddress.parse` is what turns a two-part value into 400
    /// `ADDRESS_INVALID`, never a 404 (spec §8 P16).
    private static FunctionAddress parseAddress(String raw) {
        return FunctionAddress.parse(raw);
    }

    /// `f`'s `live` alias's version, or `null` when unset — the single-row
    /// read `getOne` uses (list uses its own batch read, [#list]).
    private static FunctionVersion liveVersionOf(State s, Function f) {
        return f.liveVersionId().flatMap(s.versions()::findById).orElse(null);
    }

    /// Load-or-404 for a version already known to belong to `f` (spec §5.2).
    private static FunctionVersion versionOrNotFound(State s, Function f, int version) {
        return s.versions().findByFunctionAndVersion(f.id(), version)
                .orElseThrow(() -> HttpError.notFound("FunctionVersion", f.address().render() + "#" + version));
    }

    /// `{v}` not a positive integer ⇒ 400 `VERSION_INVALID` (spec §5.2).
    private static int parseVersionNumber(String raw) {
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw UseCaseException.validation("VERSION_INVALID", "version must be a positive integer");
        }
    }

    /// The list filter: the caller's optional narrowing (`address`,
    /// `clientId`, `status`) AND-ed with its mandatory reach (spec §4.2,
    /// `FunctionRepository.PageFilter`'s own doc). `clientId=platform`
    /// selects platform-owned functions via [FunctionOwner#fromWire].
    private static FunctionRepository.PageFilter listFilter(Exchange ctx, AuthContext ac) {
        String addressParam = queryParam(ctx, "address");
        FunctionAddressPattern pattern = addressParam == null ? null : FunctionAddressPattern.parse(addressParam);
        String clientIdParam = queryParam(ctx, "clientId");
        FunctionOwner owner = clientIdParam == null ? null : FunctionOwner.fromWire(clientIdParam);
        String statusParam = queryParam(ctx, "status");
        FunctionStatus status = statusParam == null ? null : parseStatus(statusParam);
        Visibility visibility = ac == null ? new Visibility.Tenants(List.of()) : ac.visibility();
        List<String> applicationIds = ac != null && ac.isApplicationScoped() ? ac.applications() : List.of();
        return new FunctionRepository.PageFilter(pattern, owner, status, visibility, applicationIds);
    }

    private static FunctionStatus parseStatus(String raw) {
        try {
            return FunctionStatus.parse(raw);
        } catch (IllegalArgumentException e) {
            throw UseCaseException.validation("STATUS_INVALID", "status must be ACTIVE or DISABLED");
        }
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static void rejectImmutableFields(Exchange ctx) {
        JsonNode tree = Json.MAPPER.readTree(ctx.body());
        for (String field : IMMUTABLE_FIELDS) {
            if (tree.has(field)) {
                throw UseCaseException.validation("FUNCTION_IMMUTABLE_FIELD",
                        "field '" + field + "' cannot be changed after creation");
            }
        }
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────

    /// Body of `POST /api/functions` (spec §4.1).
    public record CreateFunctionRequest(String applicationCode, String serviceName, String name, String runtime,
                                        String description, String clientId) {
        public CreateCommand toCommand() {
            return new CreateCommand(applicationCode, serviceName, name, runtime, description, clientId);
        }
    }

    /// Body of `PUT /api/functions/{address}` (spec §4.2): only these two
    /// fields exist on the DTO at all — the immutable ones are rejected
    /// before binding even reaches this record.
    public record UpdateFunctionRequest(String description, String status) {
        public UpdateCommand toCommand(FunctionAddress address) {
            return new UpdateCommand(address, description, status);
        }
    }

    /// The function response (spec §4.4). `live` is absent until a version
    /// is promoted (spec §5.2); `liveVersion` is `null` in every path that
    /// has not already loaded it — never a fresh read per row here, see
    /// [#list], [#getOne], [#create]'s own call sites.
    public record FunctionResponse(
            String id, String address, String applicationCode, String serviceName, String name,
            String applicationId, String clientId, String runtime, String description, String status,
            Live live, Instant createdAt, Instant updatedAt) {

        public static FunctionResponse from(Function f, FunctionVersion liveVersion) {
            Live live = liveVersion == null ? null : new Live(liveVersion.version(), liveVersion.id());
            return new FunctionResponse(f.id(), f.address().render(), f.address().application().value(),
                    f.address().service().value(), f.address().name().value(), f.applicationId(),
                    f.owner().clientIdOrNull(), f.runtime().wireValue(), f.description(), f.status().name(),
                    live, f.createdAt(), f.updatedAt());
        }

        public record Live(int version, String versionId) {
        }
    }

    /// Body of `POST /api/functions/{address}/versions` (spec §5.1).
    public record PublishRequest(String artifactRef, String digest, String signatureBundle, JsonNode manifest) {
        public PublishCommand toCommand(FunctionAddress address) {
            return new PublishCommand(address, artifactRef, digest, signatureBundle, manifest);
        }
    }

    /// 201 body of `POST /api/functions/{address}/versions` (spec §5.1):
    /// `{id, version, state: "PUBLISHED", digest, signer?}`.
    public record PublishResponse(String id, int version, String state, String digest,
                                  VersionResponse.SignerResponse signer) {
        static PublishResponse from(FunctionVersion v) {
            return new PublishResponse(v.id(), v.version(), "PUBLISHED", v.digest().value(),
                    VersionResponse.SignerResponse.from(v.signer()));
        }
    }

    /// 200 body of `PUT /api/functions/{address}/artifacts/{digest}` (spec
    /// `function-artifact-upload.md` §3).
    public record UploadArtifactResponse(String artifactRef, String digest, long bytes) {
    }

    /// One version, on the wire (spec §5.2): the LIST shape when `manifest`
    /// is `null` (omitted — `Json`'s `NON_ABSENT` default), the single-GET
    /// shape ("adds `manifest`") when it is not. `live` is whether THIS
    /// version is the function's current `live` alias target.
    public record VersionResponse(String id, int version, String state, String digest, String artifactRef,
                                  String pool, boolean warm, SignerResponse signer, String publishedBy,
                                  Instant publishedAt, Instant readyAt, Instant retiredAt, boolean live,
                                  JsonNode manifest) {

        public record SignerResponse(String issuer, String subject) {
            static SignerResponse from(SignerIdentity signer) {
                return signer == null ? null : new SignerResponse(signer.issuer(), signer.subject());
            }
        }

        static VersionResponse summary(FunctionVersion v, boolean live) {
            return of(v, live, null);
        }

        static VersionResponse detail(FunctionVersion v, boolean live) {
            return of(v, live, v.manifest().toJson());
        }

        private static VersionResponse of(FunctionVersion v, boolean live, JsonNode manifest) {
            return new VersionResponse(v.id(), v.version(), versionStateWire(v.state()), v.digest().value(),
                    v.artifactRef(), v.manifest().pool().value(), v.manifest().warm(), SignerResponse.from(v.signer()),
                    v.publishedBy(), v.publishedAt(), readyAtOf(v.state()), retiredAtOf(v.state()), live, manifest);
        }

        private static Instant readyAtOf(FunctionVersion.VersionState state) {
            return state instanceof FunctionVersion.VersionState.Ready ready ? ready.at() : null;
        }

        private static Instant retiredAtOf(FunctionVersion.VersionState state) {
            return state instanceof FunctionVersion.VersionState.Retired retired ? retired.at() : null;
        }
    }

    /// Body of `PUT /api/functions/{address}/aliases/{alias}` (spec §5.2).
    public record PromoteRequest(int version) {
    }

    /// 200 body of `PUT /api/functions/{address}/aliases/{alias}` (spec
    /// §5.2): `{alias, version, versionId, previousVersion?}` —
    /// `previousVersion` is the prior live version's NUMBER (not its id,
    /// unlike the `alias:changed` event, which carries `previousVersionId`),
    /// absent on a first promotion.
    public record PromoteResponse(String alias, int version, String versionId, Integer previousVersion) {
    }

    /// One entry of `GET /api/functions/{address}/aliases` (spec §5.2).
    public record AliasResponse(String alias, int version, String versionId, String updatedBy, Instant updatedAt) {
    }

    /// `GET /api/functions/{address}/status` (spec §6.3). `hosts` lists only
    /// hosts that report THIS address, and only their entries for it.
    public record StatusResponse(String address, String status, Live live, List<VersionSummary> versions,
                                 List<HostSummary> hosts, List<WiringEntry> wiring) {

        public record Live(int version) {
        }

        public record VersionSummary(int version, String state) {
        }

        public record HostSummary(String hostId, String pool, String state, Instant lastHeartbeat, boolean stale,
                                  List<LoadedSummary> loaded) {
        }

        public record LoadedSummary(int version, String state, String error) {
        }

        /// One `fn_trigger_objects` row (spec `function-invocation.md` §4, §7
        /// deliverable 5): `present` is `false` when the linked object has
        /// vanished (hand-deleted) — the next promote recreates it.
        public record WiringEntry(String kind, String code, String objectId, boolean present) {
        }
    }

    /// `GET /api/function-pools` (spec §6.3).
    public record PoolSummaryResponse(String pool, int hosts) {
    }

    // ── §1 (function-context.md, D4a): config/secrets DTOs ──────────────────

    /// `GET`/`PUT` `.../config` (spec §1, S1): `values` is the full map,
    /// `declared` is the ordered union of the live manifest's and the
    /// `?version=` candidate's `config` keys, `missing` is `declared` minus
    /// `values.keySet()`, `declaredBy` names which manifest(s) want which keys.
    public record ConfigResponse(Map<String, String> values, List<String> declared, List<String> missing,
                                 List<DeclaredByEntry> declaredBy) {
    }

    /// One manifest that contributed to `declared`/`missing` (spec §1, S1):
    /// `keys` is that manifest's OWN full `config`/`secrets` key list — not
    /// only the keys it added beyond another entry — so a caller can say,
    /// per key, which version(s) want it.
    public record DeclaredByEntry(int version, List<String> keys) {
    }

    /// Body of `PUT /api/functions/{address}/config` (spec §1): full
    /// replacement.
    public record SetConfigRequest(Map<String, String> values) {
        public SetConfigRequest {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /// `GET /api/functions/{address}/secrets` (spec §1, S1): `keys` never
    /// carries a value; `declared`/`declaredBy` are the same shape as
    /// [ConfigResponse]'s.
    public record SecretListResponse(List<SecretKeyResponse> keys, List<String> declared, List<String> missing,
                                     List<DeclaredByEntry> declaredBy) {
    }

    /// One entry of [SecretListResponse#keys] — key, `updatedAt`, `updatedBy`,
    /// never a value (spec §1).
    public record SecretKeyResponse(String key, Instant updatedAt, String updatedBy) {
    }

    /// Body of `PUT /api/functions/{address}/secrets/{key}` (spec §1).
    public record SetSecretRequest(String value) {
    }
}

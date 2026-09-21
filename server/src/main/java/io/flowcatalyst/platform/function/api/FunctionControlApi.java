package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.event.Event;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.artifact.ArtifactBlobStore;
import io.flowcatalyst.platform.function.artifact.ArtifactException;
import io.flowcatalyst.platform.function.artifact.ArtifactHttpException;
import io.flowcatalyst.platform.function.artifact.PlatformArtifactRef;
import io.flowcatalyst.platform.function.operations.DesiredState;
import io.flowcatalyst.platform.function.operations.MarkVersionReady;
import io.flowcatalyst.platform.function.operations.MarkVersionReadyCommand;
import io.flowcatalyst.platform.ingest.EventIngestMapper;
import io.flowcatalyst.platform.ingest.api.IngestApi;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_HOST_CONTROL;

/// The control-plane surface a function host calls (spec `function-api.md`
/// §6): `GET /control/functions/desired-state`, `POST
/// /control/functions/heartbeat`. Both: `requireAnchor` + `FUNCTION_HOST_CONTROL`,
/// **401 without a credential** — not the platform's usual 403
/// `UNAUTHENTICATED` (spec §2, the `RouterConfigApi` precedent: a missing
/// bearer on a control-plane route is 401, same as an ordinary bearer
/// failure elsewhere). Java-first, outside the lockfile (spec §0); every
/// route is named in `parity/surface.json` instead. `/control/` already
/// runs inside the authenticator (`Platform#isPlatformPath`) — it is not a
/// public path, so `Auth.current()` is populated exactly when a valid bearer
/// was presented, `null` otherwise.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/control/functions/desired-state` | 200 / 304 |
/// | POST | `/control/functions/heartbeat` | 204 |
public final class FunctionControlApi {

    private static final Logger LOG = LoggerFactory.getLogger(FunctionControlApi.class);

    private FunctionControlApi() {
    }

    /// `hostId`: 1-100 chars of `[A-Za-z0-9._:-]` (spec §6.2 `HOST_ID_INVALID`).
    private static final Pattern HOST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    private static final int MAX_ERROR_LENGTH = 1000;

    public record State(FunctionRepository functions, FunctionVersionRepository versions, FunctionHostRepository hosts,
                        UnitOfWork uow, ServiceAccountRepository serviceAccounts, FunctionSettingsRepository settings,
                        ApplicationRepository applications, EventTypeRepository eventTypes, EventRepository events,
                        FunctionRouteRepository routes, Optional<ArtifactBlobStore> artifactBlobStore) {
        public State {
            Objects.requireNonNull(functions, "functions");
            Objects.requireNonNull(versions, "versions");
            Objects.requireNonNull(hosts, "hosts");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(serviceAccounts, "serviceAccounts");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(eventTypes, "eventTypes");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(routes, "routes");
            Objects.requireNonNull(artifactBlobStore, "artifactBlobStore");
        }
    }

    /// 1–100 events per `/control/functions/events` call (spec §3 check 3).
    static final int MAX_EMIT_BATCH = 100;
    /// `data` ≤ 256 KiB (spec §3 check 3).
    static final int MAX_EMIT_DATA_BYTES = 256 * 1024;

    public static void register(Routes routes, State s) {
        DesiredState desiredState =
                new DesiredState(s.functions(), s.versions(), s.hosts(), s.serviceAccounts(), s.settings(), s.routes());
        routes.in(Group.API_READ).get("/control/functions/desired-state", Auth.scoped(ctx -> desiredState(ctx, desiredState)));
        routes.in(Group.API_WRITE).post("/control/functions/heartbeat", Auth.scoped(ctx -> heartbeat(ctx, s)));
        routes.in(Group.API_WRITE).post("/control/functions/events", Auth.scoped(ctx -> emitEvents(ctx, s, desiredState)));
        routes.in(Group.API_READ).get("/control/functions/artifacts/{versionId}", Auth.scoped(ctx -> downloadArtifact(ctx, s)));
    }

    // ── GET /control/functions/artifacts/{versionId} (spec `function-artifact-upload.md` §4) ──

    /// Streamed (spec §4: "memory must not scale with the artifact") —
    /// `Exchange.resultStream`, the response-side counterpart of the
    /// upload route's `Routes.putStreaming`. Keyed by version id, not
    /// `(functionId, hex)`: the host asks for "the artifact of the version
    /// you told me to run", and the platform resolves where that is.
    private static void downloadArtifact(Exchange ctx, State s) {
        if (gate(ctx)) {
            return;
        }
        ArtifactBlobStore store = s.artifactBlobStore().orElseThrow(ArtifactHttpException::storeNotConfigured);
        String versionId = ctx.pathParam("versionId");
        FunctionVersion v = s.versions().findById(versionId).orElseThrow(() -> HttpError.notFound("FunctionVersion", versionId));
        PlatformArtifactRef ref = PlatformArtifactRef.parse(v.artifactRef())
                .orElseThrow(() -> HttpError.notFound("FunctionVersion", versionId));
        Digest digest = v.digest();
        long size;
        InputStream in;
        try {
            size = store.size(ref.functionId(), digest);
            in = store.open(ref.functionId(), digest);
        } catch (ArtifactException e) {
            if (e.reason() instanceof ArtifactException.NotFound) {
                throw HttpError.notFound("FunctionVersion", versionId);
            }
            throw HttpError.internal("ARTIFACT_STORE_ERROR", "reading the artifact failed", e);
        }
        // Digest: sha256=<base64> is deliberately NOT sent (spec §4): the host already
        // knows the digest from desired state and recomputes it — a header would be a
        // second source of truth.
        ctx.contentType("application/octet-stream").status(200).resultStream(in, size);
    }

    // ── GET /control/functions/desired-state (spec §6.1) ────────────────────

    private static void desiredState(Exchange ctx, DesiredState desiredState) {
        if (gate(ctx)) {
            return;
        }
        DnsLabel pool = parsePool(ctx.queryParam("pool"));

        DesiredState.Document document = desiredState.build(pool, Instant.now());
        // Serialise ONCE: the ETag hashes exactly the bytes a 200 would return,
        // never a second, possibly-different serialisation of the same document.
        byte[] bodyBytes = Json.write(document).getBytes(StandardCharsets.UTF_8);
        String etag = "\"" + sha256Hex(bodyBytes) + "\"";

        ctx.header("ETag", etag);
        if (ETags.matches(ctx.header("If-None-Match"), etag)) {
            ctx.status(304);
            return;
        }
        ctx.contentType("application/json").status(200).result(bodyBytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── POST /control/functions/heartbeat (spec §6.2) ───────────────────────

    private static void heartbeat(Exchange ctx, State s) {
        if (gate(ctx)) {
            return;
        }
        var req = ctx.bodyAsClass(HeartbeatRequest.class);

        String hostId = req.hostId();
        if (hostId == null || !HOST_ID.matcher(hostId).matches()) {
            throw UseCaseException.validation("HOST_ID_INVALID", "hostId must be 1-100 characters of [A-Za-z0-9._:-]");
        }
        DnsLabel pool = parsePool(req.pool());
        FunctionHost.HostState state = parseHostState(req.state());
        List<FunctionHost.LoadedVersion> loaded =
                parseLoaded(req.loaded() == null ? List.of() : req.loaded());

        Instant now = Instant.now();
        Optional<FunctionHost> existing = s.hosts().findById(hostId);
        FunctionHost host = existing.isPresent()
                ? existing.get().heartbeat(state, loaded, now)
                : FunctionHost.register(hostId, pool, now).heartbeat(state, loaded, now);

        // §0 / spec §6.2 step 1: the host row alone, no event, no audit — a
        // heartbeat is telemetry every 15s per host, not a use case.
        s.uow().inTransaction(tx -> {
            s.hosts().persist(host, tx.dbTx());
            return null;
        });

        // Spec §6.2 step 2: ONLY an `ok()` entry (never FAILED — step 3) whose
        // resolved version is still `Published` becomes ready. This pre-check keeps the
        // routine case (a host re-reporting an already-Ready version) from ever calling
        // the operation; MarkVersionReady's OWN guard (review fix, slice B3) is what spec
        // §8 P6's behaviour actually lives on now, so a race between two heartbeats that
        // both pass this pre-check surfaces as MarkVersionReady's VERSION_NOT_PUBLISHED
        // conflict, caught and ignored below — not a failed heartbeat.
        for (FunctionHost.LoadedVersion lv : loaded) {
            if (!lv.state().ok()) {
                continue;
            }
            Optional<Function> fn = s.functions().findByAddress(lv.address());
            if (fn.isEmpty()) {
                continue;
            }
            Optional<FunctionVersion> version = s.versions().findByFunctionAndVersion(fn.get().id(), lv.version());
            if (version.isEmpty()) {
                continue;
            }
            if (version.get().state() instanceof FunctionVersion.VersionState.Published) {
                try {
                    MarkVersionReady.of(s.versions(), s.functions())
                            .run(s.uow(), new MarkVersionReadyCommand(version.get().id(), hostId), Auth.executionContext());
                } catch (UseCaseException e) {
                    if (!MarkVersionReady.VERSION_NOT_PUBLISHED.equals(e.code())) {
                        throw e;
                    }
                    LOG.atDebug().setMessage("heartbeat lost a race marking a version ready; ignoring")
                            .addKeyValue("versionId", version.get().id())
                            .addKeyValue("hostId", hostId)
                            .log();
                }
            }
        }

        ctx.status(204);
    }

    // ── POST /control/functions/events (spec §3) ─────────────────────────────

    /// The four checks, in order, each its own code — nothing is written
    /// unless every event of the batch passes every check (spec §3):
    /// 1. `hostId` names a host live within [FunctionHost#LIVE_WINDOW]
    ///    (`HOST_UNKNOWN`, 409).
    /// 2. The function exists, is `ACTIVE`, and `version` is its live
    ///    version or its newest published candidate IN THIS HOST'S POOL
    ///    ([DesiredState#serves] — the exact rule a host's own desired-state
    ///    document used to hand it that version, spec §6.1)
    ///    (`FUNCTION_NOT_SERVED_BY_HOST`, 409).
    /// 3. 1–100 events; each `dedupId` non-blank and unique in the batch;
    ///    `data` an object ≤ 256 KiB (400).
    /// 4. Ownership (R13): each `type` is an existing, non-archived event
    ///    type whose `application` equals the function's OWN application —
    ///    an unknown or archived type is the SAME 403 `EVENT_TYPE_NOT_OWNED`,
    ///    naming the type and both applications.
    ///
    /// Then every event is written through the SAME mapper/repository
    /// `POST /api/events/batch` uses ([EventIngestMapper], [EventRepository]),
    /// `source = "function:<address>"`, `clientId` = the function's owner
    /// (absent for a platform function) — one batch insert, so a repeated
    /// `dedupId` is the ingest path's own idempotent no-op (`ON CONFLICT DO
    /// NOTHING`), reported `SUCCESS` exactly as the ingest routes report it.
    private static void emitEvents(Exchange ctx, State s, DesiredState desiredState) {
        if (gate(ctx)) {
            return;
        }
        var req = ctx.bodyAsClass(EmitEventsRequest.class);

        FunctionHost host = requireLiveHost(s, req.hostId());
        Served served = resolveServedVersion(s, desiredState, host, req.address(), req.version());

        List<EmitEventItem> items = req.events() == null ? List.of() : req.events();
        if (items.isEmpty() || items.size() > MAX_EMIT_BATCH) {
            throw UseCaseException.validation("BATCH_SIZE_INVALID", "events must carry 1-100 items");
        }
        Set<String> seenDedup = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            EmitEventItem item = items.get(i);
            if (item.dedupId() == null || item.dedupId().isBlank()) {
                throw UseCaseException.validation("DEDUP_ID_REQUIRED", "events[" + i + "].dedupId is required");
            }
            if (!seenDedup.add(item.dedupId())) {
                throw UseCaseException.validation("DEDUP_ID_DUPLICATE",
                        "events[" + i + "].dedupId '" + item.dedupId() + "' is duplicated in this batch");
            }
            if (item.data() == null || item.data().isNull() || !item.data().isObject()) {
                throw UseCaseException.validation("EVENT_DATA_INVALID", "events[" + i + "].data must be an object");
            }
            int bytes = Json.write(item.data()).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_EMIT_DATA_BYTES) {
                throw UseCaseException.validation("EVENT_DATA_TOO_LARGE",
                        "events[" + i + "].data is " + bytes + " bytes, which exceeds the limit of " + MAX_EMIT_DATA_BYTES);
            }
        }

        String functionAppCode = s.applications().findById(served.function().applicationId())
                .map(Application::code)
                .orElseThrow(() -> new IllegalStateException(
                        "function " + served.function().id() + " names an application that no longer exists"));
        for (int i = 0; i < items.size(); i++) {
            EmitEventItem item = items.get(i);
            Optional<EventType> type = s.eventTypes().findByCode(item.type());
            boolean owned = type.isPresent() && !type.get().isArchived() && functionAppCode.equals(type.get().application());
            if (!owned) {
                String typeApp = type.map(EventType::application).orElse("(unknown event type)");
                throw UseCaseException.authorization("EVENT_TYPE_NOT_OWNED",
                        "events[" + i + "]: event type '" + item.type() + "' is owned by '" + typeApp
                                + "', not by this function's own application '" + functionAppCode + "'");
            }
        }

        // Every check passed for every event — now, and only now, write (spec §3: "nothing
        // is written unless every event passes"). One batch insert, same as the ingest routes.
        String source = "function:" + served.function().address().render();
        String clientId = served.function().owner() instanceof FunctionOwner.Client(String id) ? id : null;
        List<Event> toInsert = new ArrayList<>(items.size());
        for (EmitEventItem item : items) {
            toInsert.add(EventIngestMapper.toEvent(new EventIngestMapper.RawItem(
                    null, null, item.type(), source, item.subject(), item.data(), item.dedupId(),
                    item.correlationId(), item.causationId(), item.messageGroup(), clientId, List.of())));
        }
        s.events().insertBatch(toInsert);

        List<IngestApi.BatchResultItem> results = new ArrayList<>(toInsert.size());
        for (Event e : toInsert) {
            results.add(new IngestApi.BatchResultItem(e.id(), "SUCCESS", null));
        }
        ctx.status(201).json(new IngestApi.BatchResponse(results));
    }

    /// Check 1: `hostId` names a [FunctionHost] whose last heartbeat is
    /// within [FunctionHost#LIVE_WINDOW] of now.
    ///
    /// @throws UseCaseException conflict `HOST_UNKNOWN`
    private static FunctionHost requireLiveHost(State s, String hostId) {
        Instant now = Instant.now();
        return (hostId == null ? Optional.<FunctionHost>empty() : s.hosts().findById(hostId))
                .filter(h -> !h.lastHeartbeat().isBefore(now.minus(FunctionHost.LIVE_WINDOW)))
                .orElseThrow(() -> UseCaseException.conflict("HOST_UNKNOWN",
                        "no host named '" + hostId + "' has a heartbeat inside the live window"));
    }

    private record Served(Function function, FunctionVersion version) {
    }

    /// Check 2: the function exists, is `ACTIVE`, and `version` is served
    /// by `host`'s own pool ([DesiredState#serves]).
    ///
    /// @throws UseCaseException conflict `FUNCTION_NOT_SERVED_BY_HOST`
    private static Served resolveServedVersion(State s, DesiredState desiredState, FunctionHost host,
                                                String rawAddress, Integer versionNumber) {
        FunctionAddress address;
        try {
            address = FunctionAddress.parse(rawAddress);
        } catch (UseCaseException e) {
            throw notServed();
        }
        Function function = s.functions().findByAddress(address).orElseThrow(FunctionControlApi::notServed);
        if (function.status() != FunctionStatus.ACTIVE) {
            throw notServed();
        }
        if (versionNumber == null || versionNumber <= 0) {
            throw notServed();
        }
        FunctionVersion version = s.versions().findByFunctionAndVersion(function.id(), versionNumber)
                .orElseThrow(FunctionControlApi::notServed);
        if (!desiredState.serves(function, version, host.pool())) {
            throw notServed();
        }
        return new Served(function, version);
    }

    private static UseCaseException notServed() {
        return UseCaseException.conflict("FUNCTION_NOT_SERVED_BY_HOST",
                "this host does not currently serve that function/version");
    }

    private static FunctionHost.HostState parseHostState(String raw) {
        try {
            return FunctionHost.HostState.parse(raw);
        } catch (IllegalArgumentException e) {
            throw UseCaseException.validation("HOST_STATE_INVALID", "state must be ACTIVE or DRAINING");
        }
    }

    private static List<FunctionHost.LoadedVersion> parseLoaded(List<HeartbeatRequest.LoadedEntry> raw) {
        List<FunctionHost.LoadedVersion> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            out.add(parseLoadedEntry(raw.get(i), i));
        }
        return out;
    }

    /// Strict — unlike [FunctionHostRepository]'s stored reader, which drops
    /// a bad entry (spec §6.2: "an unreadable `loaded` entry is 400
    /// (`LOADED_INVALID` naming the index) — the stored reader is lenient,
    /// the wire is not").
    private static FunctionHost.LoadedVersion parseLoadedEntry(HeartbeatRequest.LoadedEntry entry, int index) {
        if (entry == null) {
            throw loadedInvalid(index, "entry is required");
        }
        FunctionAddress address;
        try {
            address = FunctionAddress.parse(entry.address());
        } catch (UseCaseException e) {
            throw loadedInvalid(index, "address: " + e.error().message());
        }
        if (entry.version() == null || entry.version() <= 0) {
            throw loadedInvalid(index, "version must be a positive integer");
        }
        FunctionHost.LoadState state = parseLoadState(entry, index);
        return new FunctionHost.LoadedVersion(address, entry.version(), state);
    }

    private static FunctionHost.LoadState parseLoadState(HeartbeatRequest.LoadedEntry entry, int index) {
        if (entry.state() == null) {
            throw loadedInvalid(index, "state is required");
        }
        return switch (entry.state()) {
            case "REGISTERED" -> new FunctionHost.LoadState.Registered();
            case "LOADED" -> new FunctionHost.LoadState.Loaded();
            case "FAILED" -> new FunctionHost.LoadState.Failed(truncateError(entry.error()));
            default -> throw loadedInvalid(index, "state must be REGISTERED, LOADED, or FAILED");
        };
    }

    /// `error` truncated to 1000 chars (spec §6.2); `null` becomes `""` —
    /// [FunctionHost.LoadState.Failed] requires a non-null `error`.
    private static String truncateError(String error) {
        if (error == null) {
            return "";
        }
        return error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }

    private static UseCaseException loadedInvalid(int index, String message) {
        return UseCaseException.validation("LOADED_INVALID", "loaded[" + index + "]: " + message);
    }

    // ── Shared ────────────────────────────────────────────────────────────

    /// 401 without a credential (spec §2), else `requireAnchor` +
    /// `FUNCTION_HOST_CONTROL` (403 for anything else) — the `RouterConfigApi`
    /// precedent, spelled out identically on both routes.
    ///
    /// @return `true` when a 401 was written and the caller must stop
    private static boolean gate(Exchange ctx) {
        AuthContext ac = Auth.current();
        if (ac == null) {
            HttpError.unauthorized(ctx, "authentication required");
            return true;
        }
        Checks.requireAnchor(ac);
        Checks.require(ac, FUNCTION_HOST_CONTROL);
        return false;
    }

    private static DnsLabel parsePool(String raw) {
        try {
            return DnsLabel.parse("pool", raw);
        } catch (UseCaseException e) {
            throw UseCaseException.validation("POOL_INVALID", "pool must be a DNS label");
        }
    }

    // ── Wire DTOs (spec §6.2) ────────────────────────────────────────────

    public record HeartbeatRequest(String hostId, String pool, String state, List<LoadedEntry> loaded) {
        public record LoadedEntry(String address, Integer version, String state, String error) {
        }
    }

    // ── Wire DTOs (spec §3) ──────────────────────────────────────────────

    public record EmitEventsRequest(String hostId, String address, Integer version, List<EmitEventItem> events) {
    }

    public record EmitEventItem(String type, String subject, String dedupId, JsonNode data,
                                String correlationId, String causationId, String messageGroup) {
    }
}

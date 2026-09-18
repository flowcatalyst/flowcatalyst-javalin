package io.flowcatalyst.platform.ingest.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.event.Event;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.event.api.EventApi.ContextEntryDTO;
import io.flowcatalyst.platform.ingest.AuditLogIngestMapper;
import io.flowcatalyst.platform.ingest.DispatchJobIngestMapper;
import io.flowcatalyst.platform.ingest.EventIngestMapper;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/// The SDK ingest routes (`docs/spec/sdk-ingest.md`): infrastructure batch
/// inserts a consumer app's outbox processor POSTs to. **No unit of work, no
/// domain event, no audit row** for these writes themselves (spec §1) — the
/// documented exception to `docs/usecase-envelope.md`. Every handler does
/// exactly: coarse permission (or, for audit, bare authentication) → decode
/// → per-item map/validate/resolve (whole-batch rejection on any failure,
/// spec §2) → one repository batch insert → one result per item, in order.
///
/// | Method | Path | Lockfile | Gate | Status |
/// |---|---|---|---|---|
/// | POST | `/api/events` | yes | `messaging:batch:events-write` | 201 |
/// | POST | `/api/events/batch` | yes | `messaging:batch:events-write` | 201 |
/// | POST | `/api/dispatch-jobs` | no | `messaging:batch:dispatch-jobs-write` (spec §6 D1) | 201 |
/// | POST | `/api/dispatch-jobs/batch` | no | `messaging:batch:dispatch-jobs-write` | 201 (200 if empty) |
/// | POST | `/api/audit-logs/batch` | no | any authenticated principal | 200 |
public final class IngestApi {

    static final int EVENT_BATCH_LIMIT = 1000;
    static final int DISPATCH_JOB_BATCH_LIMIT = 1000;
    static final int AUDIT_BATCH_LIMIT = 100;

    private IngestApi() {
    }

    /// The handlers' dependencies. `clientLookup` / `applicationLookup` are
    /// injectable so a test can wrap them with a call-counting spy to pin
    /// the per-request memoisation (spec §3.1, §4.3) — [#of] wires the real
    /// repositories.
    public record State(
            EventRepository eventRepo,
            DispatchJobRepository dispatchJobRepo,
            AuditLogRepository auditLogRepo,
            Function<String, Optional<Client>> clientLookup,
            Function<String, Optional<Application>> applicationLookup) {
        public State {
            Objects.requireNonNull(eventRepo, "eventRepo");
            Objects.requireNonNull(dispatchJobRepo, "dispatchJobRepo");
            Objects.requireNonNull(auditLogRepo, "auditLogRepo");
            Objects.requireNonNull(clientLookup, "clientLookup");
            Objects.requireNonNull(applicationLookup, "applicationLookup");
        }

        public static State of(EventRepository eventRepo, DispatchJobRepository dispatchJobRepo,
                                AuditLogRepository auditLogRepo, ClientRepository clientRepo,
                                ApplicationRepository applicationRepo) {
            return new State(eventRepo, dispatchJobRepo, auditLogRepo,
                    clientRepo::findByIdentifier, applicationRepo::findByCode);
        }
    }

    /// Mounts the five POST routes. The matching `GET` routes are already
    /// registered by `EventApi` / `DispatchJobApi` / `AuditLogApi` — Javalin
    /// allows the split by method.
    public static void register(Routes routes, State s) {
        routes.post("/api/events", Auth.scoped(ctx -> createEvent(ctx, s)));
        routes.post("/api/events/batch", Auth.scoped(ctx -> batchIngestEvents(ctx, s)));
        routes.post("/api/dispatch-jobs", Auth.scoped(ctx -> createDispatchJob(ctx, s)));
        routes.post("/api/dispatch-jobs/batch", Auth.scoped(ctx -> batchIngestDispatchJobs(ctx, s)));
        routes.post("/api/audit-logs/batch", Auth.scoped(ctx -> batchIngestAuditLogs(ctx, s)));
    }

    /// Mounts the SPA's own fan-out ingest at `path` (bff spec §8: `POST
    /// /bff/events/batch`), the same handler and body/behaviour as
    /// `POST /api/events/batch` — no duplicated handler body.
    public static void registerEventsBatchAt(Routes routes, String path, State s) {
        routes.post(path, Auth.scoped(ctx -> batchIngestEvents(ctx, s)));
    }

    // ── Events ───────────────────────────────────────────────────────────

    private static void createEvent(Exchange ctx, State s) {
        var ac = Auth.current();
        Checks.require(ac, Permission.BATCH_EVENTS_WRITE);
        var req = ctx.bodyAsClass(CreateEventRequest.class);

        String clientId = req.clientId();
        if (clientId == null && !ac.isAnchor() && !ac.clients().isEmpty()) {
            clientId = ac.clients().get(0); // spec §3.2: singular-only default (§5 D3)
        }
        requireClientAccess(ac, clientId);

        var event = EventIngestMapper.toEvent(new EventIngestMapper.RawItem(
                null, null, req.eventType(), req.source(), req.subject(), req.data(), req.deduplicationId(),
                req.correlationId(), req.causationId(), req.messageGroup(), clientId, contextEntries(req.contextData())));
        s.eventRepo().insertBatch(List.of(event));
        ctx.status(201).json(new CreateEventResponse(createdEvent(event), 0, false));
    }

    private static void batchIngestEvents(Exchange ctx, State s) {
        var ac = Auth.current();
        Checks.require(ac, Permission.BATCH_EVENTS_WRITE);
        var body = ctx.bodyAsClass(BatchRequest.class);
        var items = body.items();
        if (items.size() > EVENT_BATCH_LIMIT) {
            throw HttpError.badRequest("BATCH_TOO_LARGE", "max 1000 items per batch");
        }

        // Owner ruling 2026-09-06 #10a (Go ece54fe): partial success with honest per-item
        // results — an item missing type/source/data reports BAD_REQUEST in its own slot,
        // the valid items are still written (one insert, all of them or none). A tenant
        // violation still refuses the whole batch before anything is written (spec §2).
        Map<String, Optional<String>> clientCodeCache = new HashMap<>();
        List<Event> events = new ArrayList<>(items.size());
        List<Integer> eventSlot = new ArrayList<>(items.size());
        BatchResultItem[] results = new BatchResultItem[items.size()];
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            String invalid = invalidBatchEventItem(item);
            if (invalid != null) {
                results[i] = new BatchResultItem(item.id() == null ? "" : item.id(), "BAD_REQUEST", invalid);
                continue;
            }
            String clientId = item.clientId();
            if (clientId == null && item.clientCode() != null && !item.clientCode().isBlank()) {
                // §5 D2: an unknown code leaves the row unscoped (clientId null), not rejected.
                clientId = resolveClientId(clientCodeCache, s.clientLookup(), item.clientCode()).orElse(null);
            }
            requireClientAccess(ac, clientId);
            events.add(EventIngestMapper.toEvent(new EventIngestMapper.RawItem(
                    item.id(), item.specVersion(), item.type(), item.source(), item.subject(), item.data(),
                    item.deduplicationId(), item.correlationId(), item.causationId(), item.messageGroup(),
                    clientId, contextEntries(item.contextData()))));
            eventSlot.add(i);
        }
        if (!events.isEmpty()) s.eventRepo().insertBatch(events);
        for (int j = 0; j < events.size(); j++) {
            results[eventSlot.get(j)] = new BatchResultItem(events.get(j).id(), "SUCCESS", null);
        }
        ctx.status(201).json(new BatchResponse(List.of(results)));
    }

    /// The singular create's required fields, checked per batch item (Go's
    /// `validateBatchItem`): the message for the item's `BAD_REQUEST` slot, or
    /// `null` when the item is acceptable.
    private static String invalidBatchEventItem(BatchEventItem item) {
        if (item.type() == null || item.type().isBlank()) return "type is required";
        if (item.source() == null || item.source().isBlank()) return "source is required";
        if (item.data() == null || item.data().isNull()) return "data is required";
        return null;
    }

    // ── Dispatch jobs ────────────────────────────────────────────────────

    private static void createDispatchJob(Exchange ctx, State s) {
        var ac = Auth.current();
        Checks.require(ac, Permission.BATCH_DISPATCH_JOBS_WRITE);
        var req = ctx.bodyAsClass(CreateDispatchJobRequest.class);

        if (req.code() == null || req.code().isEmpty()) throw HttpError.badRequest("VALIDATION", "code is required");
        if (req.targetUrl() == null || req.targetUrl().isEmpty()) {
            throw HttpError.badRequest("VALIDATION", "targetUrl is required");
        }
        if (req.payload() == null) throw HttpError.badRequest("VALIDATION", "payload is required");
        if (req.serviceAccountId() == null) throw HttpError.badRequest("VALIDATION", "serviceAccountId is required");
        requireClientAccess(ac, req.clientId());

        var job = DispatchJobIngestMapper.toJob(new DispatchJobIngestMapper.RawItem(
                null, req.externalId(), req.kind(), req.code(), req.source(), req.subject(), req.targetUrl(),
                req.payload(), req.payloadContentType(), req.dataOnly(), req.eventId(), req.correlationId(),
                req.clientId(), req.subscriptionId(), req.serviceAccountId(), req.dispatchPoolId(), req.messageGroup(),
                req.mode(), 0, req.sequence(), req.timeoutSeconds(), req.maxRetries(), req.retryStrategy(),
                metadataFromMap(req.metadata()), req.idempotencyKey(), req.queue()));
        s.dispatchJobRepo().insertBatch(List.of(job));
        ctx.status(201).json(new CreatedResponse(job.id()));
    }

    private static void batchIngestDispatchJobs(Exchange ctx, State s) {
        var ac = Auth.current();
        Checks.require(ac, Permission.BATCH_DISPATCH_JOBS_WRITE);
        var body = ctx.bodyAsClass(DispatchJobBatchRequest.class);
        var items = body.items();
        if (items.size() > DISPATCH_JOB_BATCH_LIMIT) {
            throw HttpError.badRequest("BATCH_TOO_LARGE", "max 1000 items per batch");
        }

        List<DispatchJob> jobs = new ArrayList<>(items.size());
        for (var item : items) {
            var job = DispatchJobIngestMapper.toJob(new DispatchJobIngestMapper.RawItem(
                    item.id(), item.externalId(), item.kind(), item.code(), item.source(), item.subject(),
                    item.targetUrl(), item.payload(), item.payloadContentType(), item.dataOnly(), item.eventId(),
                    item.correlationId(), item.clientId(), item.subscriptionId(), item.serviceAccountId(),
                    item.dispatchPoolId(), item.messageGroup(), item.mode(), item.sequence(), null,
                    item.timeoutSeconds(), item.maxRetries(), null, item.metadata(), null, item.queue()));
            requireClientAccess(ac, job.clientId());
            jobs.add(job);
        }
        s.dispatchJobRepo().insertBatch(jobs);
        var response = new BatchResponse(jobs.stream().map(j -> new BatchResultItem(j.id(), "SUCCESS", null)).toList());
        if (jobs.isEmpty()) ctx.json(response); else ctx.status(201).json(response);
    }

    // ── Audit logs ───────────────────────────────────────────────────────

    private static void batchIngestAuditLogs(Exchange ctx, State s) {
        var ac = Auth.current();
        if (ac == null) {
            HttpError.unauthorized(ctx, "authentication required"); // spec §7: audit unauthenticated is 401, not the usual 403
            return;
        }
        var body = ctx.bodyAsClass(AuditBatchRequest.class);
        var items = body.items();
        if (items.size() > AUDIT_BATCH_LIMIT) {
            throw HttpError.badRequest("BATCH_TOO_LARGE", "Maximum 100 items per batch");
        }

        Map<String, Optional<String>> appCodeCache = new HashMap<>();
        Map<String, Optional<String>> clientCodeCache = new HashMap<>();
        List<BatchResultItem> results = new ArrayList<>(items.size());
        List<AuditLog> logs = new ArrayList<>(items.size());
        for (var item : items) {
            String applicationId = null;
            if (item.applicationCode() != null && !item.applicationCode().isBlank()) {
                var resolved = appCodeCache.computeIfAbsent(item.applicationCode(),
                        code -> s.applicationLookup().apply(code).map(Application::id));
                if (resolved.isEmpty()) {
                    results.add(SKIPPED);
                    continue;
                }
                applicationId = resolved.get();
            }
            String clientId = null;
            if (item.clientCode() != null && !item.clientCode().isBlank()) {
                var resolved = resolveClientId(clientCodeCache, s.clientLookup(), item.clientCode());
                if (resolved.isEmpty()) {
                    results.add(SKIPPED);
                    continue;
                }
                clientId = resolved.get();
            }
            if (clientId != null && !ac.canAccessClient(clientId)) {
                results.add(SKIPPED);
                continue;
            }
            // Owner ruling 2026-09-06 #10b (Go ece54fe): an audit entry without an actor is
            // refused in its own slot, never attributed to the caller; the rest still lands.
            if (item.principalId() == null || item.principalId().isBlank()) {
                results.add(new BatchResultItem("", "BAD_REQUEST", "principalId is required"));
                continue;
            }
            var log = AuditLogIngestMapper.toLog(item.entityType(), item.entityId(), item.operation(),
                    item.operationData(), item.principalId().strip(), item.performedAt(), applicationId, clientId);
            logs.add(log);
            results.add(new BatchResultItem(log.id(), "SUCCESS", null));
        }
        s.auditLogRepo().insertBatch(logs);
        ctx.json(new BatchResponse(results));
    }

    private static final BatchResultItem SKIPPED = new BatchResultItem("", "SKIPPED", null);

    // ── Shared helpers ───────────────────────────────────────────────────

    /// Whole-batch tenant guard (spec §2): a resolved `clientId` the caller
    /// cannot access is a 403 for the whole request, before anything is
    /// written. `null` (platform-scoped) always passes.
    private static void requireClientAccess(AuthContext ac, String clientId) {
        if (clientId != null && !ac.canAccessClient(clientId)) {
            throw HttpError.forbidden("No access to client: " + clientId);
        }
    }

    /// One [ClientRepository#findByIdentifier] per distinct code per
    /// request (spec §3.1, §4.3): `cache` is a fresh map the caller builds
    /// once per request.
    private static Optional<String> resolveClientId(Map<String, Optional<String>> cache,
                                                      Function<String, Optional<Client>> lookup, String code) {
        return cache.computeIfAbsent(code, c -> lookup.apply(c).map(Client::id));
    }

    private static List<Event.ContextEntry> contextEntries(List<ContextEntryDTO> dtos) {
        if (dtos == null) return List.of();
        return dtos.stream().map(c -> new Event.ContextEntry(c.key(), c.value())).toList();
    }

    private static CreatedEvent createdEvent(Event e) {
        // ContextEntryDTO.from(...) is package-private to event.api; construct directly instead.
        var context = e.context().stream().map(c -> new ContextEntryDTO(c.key(), c.value())).toList();
        return new CreatedEvent(e.id(), e.specVersion(), e.type(), e.source(), e.subject(), e.time(), e.data(),
                e.messageGroup(), e.correlationId(), e.causationId(), e.deduplicationId(), e.clientId(), context,
                e.createdAt());
    }

    /// The singular contract's metadata map → the entity's `[{key,value}]`
    /// list, key-sorted for a deterministic stored order (sdk-ingest spec
    /// §4.2, Go `metadataFromMap`) — the map itself leaves order unspecified.
    private static List<DispatchJob.Metadata> metadataFromMap(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return List.of();
        return metadata.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> new DispatchJob.Metadata(en.getKey(), en.getValue()))
                .toList();
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────

    /// The common batch result shape (spec §2), shared by all three ingest
    /// routes: `{id, status, error?}`; `error` is never set (Go never sets
    /// it either — kept on the shape for lockfile/SDK parity).
    public record BatchResultItem(String id, String status, @JsonInclude(JsonInclude.Include.NON_EMPTY) String error) {
    }

    /// `{results: [...]}` — shared by all three ingest routes.
    public record BatchResponse(List<BatchResultItem> results) {
    }

    // -- Events (lockfile: createEvent / batchIngestEvents) --

    public record CreateEventRequest(
            String eventType,
            String source,
            String subject,
            JsonNode data,
            String messageGroup,
            String correlationId,
            String causationId,
            String deduplicationId,
            String clientId,
            List<ContextEntryDTO> contextData) {
    }

    public record BatchEventItem(
            String id,
            String type,
            String source,
            String subject,
            String specVersion,
            JsonNode data,
            String deduplicationId,
            String correlationId,
            String causationId,
            String messageGroup,
            String clientId,
            String clientCode,
            List<ContextEntryDTO> contextData) {
    }

    public record BatchRequest(List<BatchEventItem> items) {
        public BatchRequest {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record CreatedEvent(
            String id,
            String specVersion,
            String eventType,
            String source,
            String subject,
            Instant time,
            JsonNode data,
            String messageGroup,
            String correlationId,
            String causationId,
            String deduplicationId,
            String clientId,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ContextEntryDTO> contextData,
            Instant createdAt) {
        public CreatedEvent {
            contextData = contextData == null ? List.of() : List.copyOf(contextData);
        }
    }

    public record CreateEventResponse(CreatedEvent event, long dispatchJobCount, boolean isDuplicate) {
    }

    // -- Dispatch jobs (outside the lockfile — spec §4.1/§4.2) --

    public record DispatchJobBatchItem(
            String id,
            String externalId,
            String kind,
            String code,
            String source,
            String subject,
            String targetUrl,
            String payload,
            String payloadContentType,
            boolean dataOnly,
            String eventId,
            String correlationId,
            String clientId,
            String subscriptionId,
            String serviceAccountId,
            String dispatchPoolId,
            String messageGroup,
            String mode,
            int sequence,
            int timeoutSeconds,
            int maxRetries,
            List<DispatchJob.Metadata> metadata,
            String queue) {
    }

    public record DispatchJobBatchRequest(List<DispatchJobBatchItem> items) {
        public DispatchJobBatchRequest {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record CreateDispatchJobRequest(
            String source,
            String kind,
            String code,
            String subject,
            String eventId,
            String correlationId,
            String targetUrl,
            String payload,
            String payloadContentType,
            boolean dataOnly,
            String serviceAccountId,
            String clientId,
            String subscriptionId,
            String mode,
            String dispatchPoolId,
            String messageGroup,
            Integer sequence,
            int timeoutSeconds,
            int maxRetries,
            String retryStrategy,
            String idempotencyKey,
            String externalId,
            Map<String, String> metadata,
            String queue) {
    }

    public record CreatedResponse(String id) {
    }

    // -- Audit logs (outside the lockfile — spec §4.3) --

    public record AuditBatchItem(
            String entityType,
            String entityId,
            String operation,
            JsonNode operationData,
            String principalId,
            String performedAt,
            String applicationCode,
            String clientCode) {
    }

    public record AuditBatchRequest(List<AuditBatchItem> items) {
        public AuditBatchRequest {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}

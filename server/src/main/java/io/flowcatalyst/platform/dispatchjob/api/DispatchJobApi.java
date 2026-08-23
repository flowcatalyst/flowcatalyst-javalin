package io.flowcatalyst.platform.dispatchjob.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.flowcatalyst.platform.dispatchjob.Attempt;
import io.flowcatalyst.platform.dispatchjob.CodeFacets;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobProjection;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.AccessScope;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.Facet;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository.ListFilter;
import io.flowcatalyst.platform.dispatchjob.operations.RequeueCommand;
import io.flowcatalyst.platform.dispatchjob.operations.RequeueDispatchJobs;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.apicommon.QueryParams;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.DISPATCH_JOB_VIEW;
import static io.flowcatalyst.platform.shared.auth.Permission.DISPATCH_JOB_VIEW_RAW;

/// The `/api/dispatch-jobs` surface (spec §3). Reads go straight to the
/// repository with SQL-side tenant scoping; the one write (requeue) does
/// exactly: coarse permission → command from DTO → `Operation.run` →
/// response. Every handler runs inside [Auth#scoped].
///
/// | Method | Path | Gate | Status |
/// |---|---|---|---|
/// | GET | `/api/dispatch-jobs` | view | 200 `[DispatchJobRead]` |
/// | GET | `/api/dispatch-jobs/list-raw` | view-raw | 200 `[DispatchJobRead]` |
/// | GET | `/api/dispatch-jobs/raw` | view-raw | 200 `[DispatchJobRead]` (SDK alias) |
/// | GET | `/api/dispatch-jobs/filter-options` | view | 200 [DispatchJobFilterOptionsResponse] |
/// | GET | `/api/dispatch-jobs/event/{eventId}` | view | 200 `[DispatchJobRead]` |
/// | GET | `/api/dispatch-jobs/by-event/{eventId}` | view | 200 `[DispatchJobRead]` (SDK alias) |
/// | GET | `/api/dispatch-jobs/{id}` | view | 200 [DispatchJobResponse] |
/// | GET | `/api/dispatch-jobs/{id}/raw` | view-raw | 200 [DispatchJobResponse] |
/// | GET | `/api/dispatch-jobs/{id}/attempts` | view | 200 `[AttemptDTO]` |
/// | POST | `/api/dispatch-jobs/requeue` | view | 200 [RequeueResponse] |
public final class DispatchJobApi {

    /// Distinct values per facet on `filter-options` (spec §8).
    static final int FACET_LIMIT = 200;

    private DispatchJobApi() {
    }

    /// The handlers' dependencies.
    public record State(DispatchJobRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    /// The literal segments are registered before `{id}` so they win.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/dispatch-jobs", Auth.scoped(ctx -> list(ctx, s, DISPATCH_JOB_VIEW)));
        Handler listRaw = Auth.scoped(ctx -> list(ctx, s, DISPATCH_JOB_VIEW_RAW));
        routes.get("/api/dispatch-jobs/list-raw", listRaw);
        routes.get("/api/dispatch-jobs/raw", listRaw); // SDK alias of list-raw (Laravel client)
        routes.get("/api/dispatch-jobs/filter-options", Auth.scoped(ctx -> filterOptions(ctx, s)));
        Handler byEvent = Auth.scoped(ctx -> byEvent(ctx, s));
        routes.get("/api/dispatch-jobs/event/{eventId}", byEvent);
        routes.get("/api/dispatch-jobs/by-event/{eventId}", byEvent); // SDK alias of event/{eventId}
        routes.post("/api/dispatch-jobs/requeue", Auth.scoped(ctx -> requeue(ctx, s)));
        routes.get("/api/dispatch-jobs/{id}", Auth.scoped(ctx -> getById(ctx, s, DISPATCH_JOB_VIEW)));
        routes.get("/api/dispatch-jobs/{id}/raw", Auth.scoped(ctx -> getById(ctx, s, DISPATCH_JOB_VIEW_RAW)));
        routes.get("/api/dispatch-jobs/{id}/attempts", Auth.scoped(ctx -> attempts(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    /// The three list routes share one handler; only the gate differs (spec §3).
    private static void list(Context ctx, State s, Permission gate) {
        AuthContext ac = Auth.current();
        Checks.require(ac, gate);
        ctx.json(s.repo().findWithFilters(listFilter(ctx, ac)).stream().map(DispatchJobRead::from).toList());
    }

    /// Detail and raw detail share one handler; only the gate differs (spec §3).
    private static void getById(Context ctx, State s, Permission gate) {
        AuthContext ac = Auth.current();
        Checks.require(ac, gate);
        ctx.json(DispatchJobResponse.from(accessible(ac, s, ctx.pathParam("id"))));
    }

    private static void attempts(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, DISPATCH_JOB_VIEW);
        DispatchJob job = accessible(ac, s, ctx.pathParam("id")); // 404 + scope before exposing the history
        ctx.json(s.repo().attemptsByJob(job.id()).stream().map(AttemptDTO::from).toList());
    }

    /// Platform-scoped jobs (`null` client) are visible to anchors / super-admins only here.
    private static void byEvent(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, DISPATCH_JOB_VIEW);
        ctx.json(s.repo().findByEventId(ctx.pathParam("eventId")).stream()
                .filter(p -> Checks.canAccessScope(ac, p.clientId()))
                .map(DispatchJobRead::from).toList());
    }

    private static void filterOptions(Context ctx, State s) {
        Checks.require(Auth.current(), DISPATCH_JOB_VIEW);
        ctx.json(DispatchJobFilterOptionsResponse.from(s.repo()));
    }

    private static void requeue(Context ctx, State s) {
        Checks.require(Auth.current(), DISPATCH_JOB_VIEW); // a caller who can see a job may re-drive it (spec §3)
        var cmd = ctx.bodyAsClass(RequeueRequest.class).toCommand();
        var event = RequeueDispatchJobs.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(new RequeueResponse(event.requeued()));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Load-or-404 plus the per-resource scope check every by-id read applies (spec §5, §7).
    private static DispatchJob accessible(AuthContext ac, State s, String id) {
        DispatchJob job = s.repo().findById(id).orElseThrow(() -> HttpError.notFound("DispatchJob", id));
        Checks.checkScopeAccess(ac, job.clientId());
        return job;
    }

    /// Query params → filter (spec §4), plus the caller's SQL-side scope.
    private static ListFilter listFilter(Context ctx, AuthContext ac) {
        Page page = Page.from(ctx);
        return new ListFilter(
                queryParam(ctx, "status"),
                queryParam(ctx, "clientId"),
                queryParam(ctx, "dispatchPoolId"),
                queryParam(ctx, "subscriptionId"),
                queryParam(ctx, "code"),
                queryParam(ctx, "source"),
                timestamp(queryParam(ctx, "since")),
                timestamp(queryParam(ctx, "until")),
                "createdAt.asc".equals(queryParam(ctx, "sort")),
                page.effectiveLimit(),
                page.offset(),
                csv(queryParam(ctx, "clientIds")),
                csv(queryParam(ctx, "statuses")),
                csv(queryParam(ctx, "codes")),
                csv(queryParam(ctx, "applications")),
                csv(queryParam(ctx, "subdomains")),
                csv(queryParam(ctx, "aggregates")),
                scope(ac));
    }

    /// Anchors are unscoped; everyone else sees platform-scoped rows plus their own clients'.
    private static AccessScope scope(AuthContext ac) {
        return ac.isAnchor() ? new AccessScope.Unscoped() : new AccessScope.Clients(ac.clients());
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /// `limit` / `size` / `offset` as sent (0 = absent): `size` wins when
    /// positive; the repository's guard supplies the default and the
    /// over-max fallback (spec §4, §8). A non-integer value is the
    /// [QueryParams] 400 `VALIDATION` envelope listing every bad parameter,
    /// in `limit, offset, size` order.
    record Page(int limit, int offset, int size) {
        static Page from(Context ctx) {
            var errors = new ArrayList<Map<String, Object>>();
            int limit = QueryParams.intParam(ctx, "limit", errors).orElse(0);
            int offset = QueryParams.intParam(ctx, "offset", errors).orElse(0);
            int size = QueryParams.intParam(ctx, "size", errors).orElse(0);
            if (!errors.isEmpty()) throw QueryParams.validation(errors);
            return new Page(limit, offset, size);
        }

        /// The row cap handed to the repository: the SPA's `size` when positive, else the SDK's `limit`.
        int effectiveLimit() {
            return size > 0 ? size : limit;
        }
    }

    /// RFC 3339 with any offset → instant; `null` or unparseable → `null` (spec §4, open question 8).
    private static Instant timestamp(String raw) {
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /// Comma-separated → trimmed, non-blank parts; `null` → empty list (no filter).
    private static List<String> csv(String value) {
        if (value == null) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/dispatch-jobs/requeue`.
    public record RequeueRequest(List<String> ids) {
        public RequeueCommand toCommand() {
            return new RequeueCommand(ids);
        }
    }

    /// `{requeued}` — rows actually reset.
    public record RequeueResponse(long requeued) {
    }

    /// The slim list shape, one per projection row. `dispatchMode` always
    /// equals `mode` (legacy duplicate); `application / subdomain / aggregate`
    /// are derived from the code; the schema's `clientIdentifier` and
    /// `priority` are never emitted (spec §3).
    public record DispatchJobRead(
            String id,
            String eventId,
            String subscriptionId,
            String clientId,
            String application,
            String subdomain,
            String aggregate,
            String code,
            String source,
            String subject,
            String status,
            String kind,
            String targetUrl,
            String mode,
            String dispatchMode,
            String correlationId,
            Instant scheduledFor,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Instant lastAttemptAt,
            int attemptCount) {

        public static DispatchJobRead from(DispatchJobProjection p) {
            CodeFacets facets = p.facets();
            return new DispatchJobRead(p.id(), p.eventId(), p.subscriptionId(), p.clientId(),
                    facets.application(), facets.subdomain(), facets.aggregate(), p.code(), p.source(), p.subject(),
                    p.status().name(), p.kind().name(), p.targetUrl(), p.mode().name(), p.mode().name(),
                    p.correlationId(), p.scheduledFor(), p.createdAt(), p.updatedAt(), p.completedAt(),
                    p.lastAttemptAt(), p.attemptCount());
        }
    }

    /// The full detail shape; `attempts` and `metadata` are omitted when
    /// empty (never `[]`), and `attempts` is never hydrated today (spec §1.1).
    public record DispatchJobResponse(
            String id,
            String externalId,
            String kind,
            String code,
            String source,
            String subject,
            String targetUrl,
            String protocol,
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
            String schemaId,
            int maxRetries,
            String retryStrategy,
            String status,
            int attemptCount,
            String lastError,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AttemptDTO> attempts,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MetadataDTO> metadata,
            String idempotencyKey,
            Instant createdAt,
            Instant updatedAt,
            Instant scheduledFor,
            Instant expiresAt,
            Instant lastAttemptAt,
            Instant completedAt,
            Long durationMillis) {

        public static DispatchJobResponse from(DispatchJob j) {
            return new DispatchJobResponse(j.id(), j.externalId(), j.kind().name(), j.code(), j.source(), j.subject(),
                    j.targetUrl(), j.protocol().name(), j.payload(), j.payloadContentType(), j.dataOnly(), j.eventId(),
                    j.correlationId(), j.clientId(), j.subscriptionId(), j.serviceAccountId(), j.dispatchPoolId(),
                    j.messageGroup(), j.mode().name(), j.sequence(), j.timeoutSeconds(), j.schemaId(), j.maxRetries(),
                    j.retryStrategy().wire(), j.status().name(), j.attemptCount(), j.lastError(), List.of(),
                    j.metadata().stream().map(MetadataDTO::from).toList(), j.idempotencyKey(), j.createdAt(),
                    j.updatedAt(), j.scheduledFor(), j.expiresAt(), j.lastAttemptAt(), j.completedAt(),
                    j.durationMillis());
        }
    }

    /// One attempt on the wire.
    public record AttemptDTO(
            int attemptNumber,
            Instant attemptedAt,
            Instant completedAt,
            Long durationMillis,
            Integer responseCode,
            String responseBody,
            boolean success,
            String errorMessage,
            String errorType) {

        public static AttemptDTO from(Attempt a) {
            return new AttemptDTO(a.attemptNumber(), a.attemptedAt(), a.completedAt(), a.durationMillis(),
                    a.responseCode(), a.responseBody(), a.success(), a.errorMessage(),
                    a.errorType() == null ? null : a.errorType().name());
        }
    }

    /// One `{key, value}` tag.
    public record MetadataDTO(String key, String value) {
        public static MetadataDTO from(DispatchJob.Metadata m) {
            return new MetadataDTO(m.key(), m.value());
        }
    }

    /// The six facets of `filter-options`; every list is present, possibly empty.
    public record DispatchJobFilterOptionsResponse(
            List<String> statuses,
            List<String> codes,
            List<String> clientIds,
            List<String> dispatchPoolIds,
            List<String> subscriptionIds,
            List<String> kinds) {

        public DispatchJobFilterOptionsResponse {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            codes = codes == null ? List.of() : List.copyOf(codes);
            clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
            dispatchPoolIds = dispatchPoolIds == null ? List.of() : List.copyOf(dispatchPoolIds);
            subscriptionIds = subscriptionIds == null ? List.of() : List.copyOf(subscriptionIds);
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
        }

        static DispatchJobFilterOptionsResponse from(DispatchJobRepository repo) {
            return new DispatchJobFilterOptionsResponse(
                    repo.distinctValues(Facet.STATUS, FACET_LIMIT),
                    repo.distinctValues(Facet.CODE, FACET_LIMIT),
                    repo.distinctValues(Facet.CLIENT_ID, FACET_LIMIT),
                    repo.distinctValues(Facet.DISPATCH_POOL_ID, FACET_LIMIT),
                    repo.distinctValues(Facet.SUBSCRIPTION_ID, FACET_LIMIT),
                    repo.distinctValues(Facet.KIND, FACET_LIMIT));
        }
    }
}

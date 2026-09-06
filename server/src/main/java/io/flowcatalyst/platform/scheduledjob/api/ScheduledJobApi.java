package io.flowcatalyst.platform.scheduledjob.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.scheduledjob.InstanceStatus;
import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceLog;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.ClientFilter;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.ListFilter;
import io.flowcatalyst.platform.scheduledjob.operations.ArchiveCommand;
import io.flowcatalyst.platform.scheduledjob.operations.ArchiveScheduledJob;
import io.flowcatalyst.platform.scheduledjob.operations.CreateCommand;
import io.flowcatalyst.platform.scheduledjob.operations.CreateScheduledJob;
import io.flowcatalyst.platform.scheduledjob.operations.DeleteCommand;
import io.flowcatalyst.platform.scheduledjob.operations.DeleteScheduledJob;
import io.flowcatalyst.platform.scheduledjob.operations.FireNow;
import io.flowcatalyst.platform.scheduledjob.operations.FireNowCommand;
import io.flowcatalyst.platform.scheduledjob.operations.PauseCommand;
import io.flowcatalyst.platform.scheduledjob.operations.PauseScheduledJob;
import io.flowcatalyst.platform.scheduledjob.operations.ResumeCommand;
import io.flowcatalyst.platform.scheduledjob.operations.ResumeScheduledJob;
import io.flowcatalyst.platform.scheduledjob.operations.UpdateCommand;
import io.flowcatalyst.platform.scheduledjob.operations.UpdateScheduledJob;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.apicommon.OffsetPage;
import io.flowcatalyst.platform.shared.apicommon.PageQuery;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/api/scheduled-jobs` surface (spec §4). A write handler does
/// exactly: coarse permission → command from DTO → `Operation.run` →
/// response. Reads go straight to the repositories and apply the visibility
/// rule here; the two instance callbacks (`/log`, `/complete`) are direct
/// projection writes (spec §6), scope-checked on the instance's client.
/// Every handler runs inside [Auth#scoped] so the operations can read
/// [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/scheduled-jobs` | 200 [OffsetPage] of [ScheduledJobResponse] |
/// | POST | `/api/scheduled-jobs` | 201 [CreatedResponse] |
/// | GET | `/api/scheduled-jobs/by-code/{code}` | 200 [ScheduledJobResponse] |
/// | GET | `/api/scheduled-jobs/instances/{instanceId}` | 200 [ScheduledJobInstanceResponse] |
/// | GET | `/api/scheduled-jobs/instances/{instanceId}/logs` | 200 `[`[ScheduledJobInstanceLogResponse]`]` |
/// | POST | `/api/scheduled-jobs/instances/{instanceId}/log` | 204 |
/// | POST | `/api/scheduled-jobs/instances/{instanceId}/complete` | 204 |
/// | GET | `/api/scheduled-jobs/{id}` | 200 [ScheduledJobResponse] |
/// | PUT | `/api/scheduled-jobs/{id}` | 204 |
/// | DELETE | `/api/scheduled-jobs/{id}` | 204 |
/// | POST | `/api/scheduled-jobs/{id}/pause` | 204 |
/// | POST | `/api/scheduled-jobs/{id}/resume` | 204 |
/// | POST | `/api/scheduled-jobs/{id}/archive` | 204 |
/// | POST | `/api/scheduled-jobs/{id}/fire` | 202 [FireNowResponse] |
/// | GET | `/api/scheduled-jobs/{id}/instances` | 200 [OffsetPage] of [ScheduledJobInstanceResponse] |
public final class ScheduledJobApi {

    private ScheduledJobApi() {
    }

    /// The handlers' dependencies.
    public record State(ScheduledJobRepository repo, ScheduledJobInstanceRepository instances, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(instances, "instances");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the
    /// lockfile's. The literal segments (`by-code`, `instances`) are
    /// registered before `{id}` so they win.
    public static void register(Routes routes, State s) {
        routes.get("/api/scheduled-jobs", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/scheduled-jobs", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/scheduled-jobs/by-code/{code}", Auth.scoped(ctx -> getByCode(ctx, s)));
        routes.get("/api/scheduled-jobs/instances/{instanceId}", Auth.scoped(ctx -> getInstance(ctx, s)));
        routes.get("/api/scheduled-jobs/instances/{instanceId}/logs", Auth.scoped(ctx -> listInstanceLogs(ctx, s)));
        routes.post("/api/scheduled-jobs/instances/{instanceId}/log", Auth.scoped(ctx -> writeInstanceLog(ctx, s)));
        routes.post("/api/scheduled-jobs/instances/{instanceId}/complete", Auth.scoped(ctx -> completeInstance(ctx, s)));
        routes.get("/api/scheduled-jobs/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.put("/api/scheduled-jobs/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.delete("/api/scheduled-jobs/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.post("/api/scheduled-jobs/{id}/pause", Auth.scoped(ctx -> pause(ctx, s)));
        routes.post("/api/scheduled-jobs/{id}/resume", Auth.scoped(ctx -> resume(ctx, s)));
        routes.post("/api/scheduled-jobs/{id}/archive", Auth.scoped(ctx -> archive(ctx, s)));
        routes.post("/api/scheduled-jobs/{id}/fire", Auth.scoped(ctx -> fireNow(ctx, s)));
        routes.get("/api/scheduled-jobs/{id}/instances", Auth.scoped(ctx -> listInstances(ctx, s)));
    }

    // ── Job handlers ───────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        PageQuery page = PageQuery.from(ctx);
        ListFilter filter = listFilter(ctx, ac);
        List<ScheduledJob> rows = s.repo().findWithFilters(filter, page.pageSize(), (int) page.offset());
        ctx.json(OffsetPage.of(rows.stream().map(j -> response(s, j)).toList(), page, s.repo().countWithFilters(filter)));
    }

    private static void getById(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        ctx.json(response(s, visible(ac, job(s, ctx.pathParam("id")))));
    }

    /// `?clientId` selects the scope; absent = the platform-scoped job of that code.
    private static void getByCode(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        ctx.json(response(s, visible(ac, jobByCode(s, ctx.pathParam("code"), queryParam(ctx, "clientId")))));
    }

    private static void create(Exchange ctx, State s) {
        requireWrite(Auth.current());
        var cmd = ctx.bodyAsClass(CreateScheduledJobRequest.class).toCommand();
        var event = CreateScheduledJob.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.scheduledJobId()));
    }

    private static void update(Exchange ctx, State s) {
        requireWrite(Auth.current());
        var cmd = ctx.bodyAsClass(UpdateScheduledJobRequest.class).toCommand(ctx.pathParam("id"));
        UpdateScheduledJob.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), SCHEDULED_JOB_DELETE);
        DeleteScheduledJob.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void pause(Exchange ctx, State s) {
        requireWrite(Auth.current());
        PauseScheduledJob.of(s.repo()).run(s.uow(), new PauseCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void resume(Exchange ctx, State s) {
        requireWrite(Auth.current());
        ResumeScheduledJob.of(s.repo()).run(s.uow(), new ResumeCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void archive(Exchange ctx, State s) {
        requireWrite(Auth.current());
        ArchiveScheduledJob.of(s.repo()).run(s.uow(), new ArchiveCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    /// The body is optional: a bodiless fire carries no correlation id.
    private static void fireNow(Exchange ctx, State s) {
        Checks.require(Auth.current(), SCHEDULED_JOB_FIRE);
        var cmd = new FireNowCommand(ctx.pathParam("id"),
                ctx.body().isBlank() ? null : ctx.bodyAsClass(FireNowRequest.class).correlationId());
        var event = FireNow.of(s.repo(), s.instances()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(202).json(new FireNowResponse(event.instanceId(), event.scheduledJobId(), event.instanceId()));
    }

    // ── Instance handlers ──────────────────────────────────────────────────

    private static void listInstances(Exchange ctx, State s) {
        Checks.require(Auth.current(), SCHEDULED_JOB_VIEW);
        PageQuery page = PageQuery.from(ctx);
        String status = queryParam(ctx, "status");
        var filter = ScheduledJobInstanceRepository.ListFilter.forJob(ctx.pathParam("id"),
                status == null ? null : InstanceStatus.parseWire(status));
        var rows = s.instances().list(filter, page.pageSize(), (int) page.offset());
        ctx.json(OffsetPage.of(rows.stream().map(ScheduledJobInstanceResponse::from).toList(), page, s.instances().count(filter)));
    }

    private static void getInstance(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        ctx.json(ScheduledJobInstanceResponse.from(visible(ac, instance(s, ctx.pathParam("instanceId")))));
    }

    /// A bare JSON array, oldest first, capped at [ScheduledJobInstanceRepository#MAX_LOGS].
    private static void listInstanceLogs(Exchange ctx, State s) {
        Checks.require(Auth.current(), SCHEDULED_JOB_VIEW);
        ctx.json(s.instances().listLogs(ctx.pathParam("instanceId"), ScheduledJobInstanceRepository.MAX_LOGS).stream()
                .map(ScheduledJobInstanceLogResponse::from).toList());
    }

    private static void writeInstanceLog(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        requireWrite(ac);
        ScheduledJobInstance inst = instance(s, ctx.pathParam("instanceId"));
        Checks.checkScopeAccess(ac, inst.clientId());
        s.instances().writeLog(ctx.bodyAsClass(WriteInstanceLogRequest.class).toLog(inst));
        ctx.status(204);
    }

    private static void completeInstance(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        requireWrite(ac);
        ScheduledJobInstance inst = instance(s, ctx.pathParam("instanceId"));
        Checks.checkScopeAccess(ac, inst.clientId());
        var c = ctx.bodyAsClass(CompleteInstanceRequest.class).resolve();
        s.instances().markComplete(inst.id(), c.status(), c.completionStatus(), c.result());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// "write" = any of create / update / delete (spec §4).
    private static void requireWrite(AuthContext ac) {
        Checks.requireAny(ac, SCHEDULED_JOB_CREATE, SCHEDULED_JOB_UPDATE, SCHEDULED_JOB_DELETE);
    }

    /// Query params → filter (spec §4): `clientId=platform` selects
    /// platform-scoped rows; the caller's visibility is part of the filter so
    /// `total` and the page agree.
    private static ListFilter listFilter(Exchange ctx, AuthContext ac) {
        String clientId = queryParam(ctx, "clientId");
        ClientFilter client = clientId == null ? new ClientFilter.Any()
                : clientId.equals("platform") ? new ClientFilter.PlatformOnly() : new ClientFilter.Of(clientId);
        return new ListFilter(client, queryParam(ctx, "status"), queryParam(ctx, "search"), ac.visibility());
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static ScheduledJob job(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("ScheduledJob", id));
    }

    private static ScheduledJob jobByCode(State s, String code, String clientId) {
        return s.repo().findByCode(code, clientId).orElseThrow(() -> HttpError.notFound("ScheduledJob", code));
    }

    private static ScheduledJobInstance instance(State s, String id) {
        return s.instances().findById(id).orElseThrow(() -> HttpError.notFound("ScheduledJobInstance", id));
    }

    /// A client-scoped job is visible only to principals with access to that client.
    private static ScheduledJob visible(AuthContext ac, ScheduledJob j) {
        if (j.isPlatformScoped() || ac.canAccessClient(j.clientId())) return j;
        throw HttpError.forbidden("No access to this scheduled job");
    }

    private static ScheduledJobInstance visible(AuthContext ac, ScheduledJobInstance i) {
        if (i.isPlatformScoped() || ac.canAccessClient(i.clientId())) return i;
        throw HttpError.forbidden("No access to this instance");
    }

    private static ScheduledJobResponse response(State s, ScheduledJob j) {
        return ScheduledJobResponse.from(j, s.instances().hasActiveInstance(j.id(), j.tracksCompletion()));
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/scheduled-jobs`.
    public record CreateScheduledJobRequest(String code, String name, List<String> crons, String timezone,
                                            String clientId, String applicationId, String description,
                                            JsonNode payload, boolean concurrent, boolean tracksCompletion,
                                            Integer timeoutSeconds, Integer deliveryMaxAttempts, String targetUrl) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, crons, timezone, clientId, applicationId, description, payload,
                    concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl);
        }
    }

    /// Body of `PUT /api/scheduled-jobs/{id}`; absent fields are untouched.
    public record UpdateScheduledJobRequest(String name, String description, List<String> crons, String timezone,
                                            JsonNode payload, Boolean concurrent, Boolean tracksCompletion,
                                            Integer timeoutSeconds, Integer deliveryMaxAttempts, String targetUrl) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description, crons, timezone, payload, concurrent, tracksCompletion,
                    timeoutSeconds, deliveryMaxAttempts, targetUrl);
        }
    }

    /// The wire shape of one job; optional fields are omitted when `null`.
    /// `hasActiveInstance` drives the dashboard's "currently running" badge.
    public record ScheduledJobResponse(
            String id,
            String clientId,
            String applicationId,
            String code,
            String name,
            String description,
            String status,
            List<String> crons,
            String timezone,
            JsonNode payload,
            boolean concurrent,
            boolean tracksCompletion,
            Integer timeoutSeconds,
            int deliveryMaxAttempts,
            String targetUrl,
            Instant lastFiredAt,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String updatedBy,
            int version,
            boolean hasActiveInstance) {

        public static ScheduledJobResponse from(ScheduledJob j, boolean hasActiveInstance) {
            return new ScheduledJobResponse(j.id(), j.clientId(), j.applicationId(), j.code(), j.name(), j.description(),
                    j.status().name(), j.crons(), j.timezone(), j.payload(), j.concurrent(), j.tracksCompletion(),
                    j.timeoutSeconds(), j.deliveryMaxAttempts(), j.targetUrl(), j.lastFiredAt(), j.createdAt(),
                    j.updatedAt(), j.createdBy(), j.updatedBy(), j.version(), hasActiveInstance);
        }
    }

    /// Optional body of `POST /api/scheduled-jobs/{id}/fire`.
    public record FireNowRequest(String correlationId) {
    }

    /// `id` duplicates `instanceId`: the SPA's fire toast reads `result.id`.
    public record FireNowResponse(String id, String scheduledJobId, String instanceId) {
    }

    /// The wire shape of one firing.
    public record ScheduledJobInstanceResponse(
            String id,
            String scheduledJobId,
            String clientId,
            String jobCode,
            String triggerKind,
            Instant scheduledFor,
            Instant firedAt,
            Instant deliveredAt,
            Instant completedAt,
            String status,
            int deliveryAttempts,
            String deliveryError,
            String completionStatus,
            JsonNode completionResult,
            String correlationId,
            Instant createdAt) {

        public static ScheduledJobInstanceResponse from(ScheduledJobInstance i) {
            return new ScheduledJobInstanceResponse(i.id(), i.scheduledJobId(), i.clientId(), i.jobCode(),
                    i.triggerKind().name(), i.scheduledFor(), i.firedAt(), i.deliveredAt(), i.completedAt(),
                    i.status().name(), i.deliveryAttempts(), i.deliveryError(), i.completionStatus(),
                    i.completionResult(), i.correlationId(), i.createdAt());
        }
    }

    /// The wire shape of one log line.
    public record ScheduledJobInstanceLogResponse(String id, String instanceId, String scheduledJobId, String clientId,
                                                  String level, String message, JsonNode metadata, Instant createdAt) {
        public static ScheduledJobInstanceLogResponse from(ScheduledJobInstanceLog l) {
            return new ScheduledJobInstanceLogResponse(l.id(), l.instanceId(), l.scheduledJobId(), l.clientId(),
                    l.level(), l.message(), l.metadata(), l.createdAt());
        }
    }

    /// Body of `POST …/instances/{instanceId}/log`.
    public record WriteInstanceLogRequest(String level, String message, JsonNode metadata) {
        ScheduledJobInstanceLog toLog(ScheduledJobInstance inst) {
            UseCaseException.requireNonBlank(level, "LEVEL_REQUIRED", "level is required");
            UseCaseException.requireNonBlank(message, "MESSAGE_REQUIRED", "message is required");
            return ScheduledJobInstanceLog.on(inst, level, message, metadata);
        }
    }

    /// Body of `POST …/instances/{instanceId}/complete` — two dialects
    /// (spec §6.2): the SDK's `{status: SUCCESS|FAILURE, result}` where
    /// `status` is the completion *outcome*, and the SPA's
    /// `{status: <instance status>, completionStatus, completionResult}`.
    /// `SUCCESS`/`FAILURE` never collide with an instance status, so the
    /// value alone disambiguates; an explicit `completionStatus` always wins;
    /// `result` is the SDK alias of `completionResult`.
    public record CompleteInstanceRequest(String status, String completionStatus, JsonNode completionResult, JsonNode result) {

        /// The resolved outcome: instance status, completion status (`null` = none) and result payload.
        public record Completion(InstanceStatus status, String completionStatus, JsonNode result) {
        }

        public Completion resolve() {
            String outcome = completionStatus == null || completionStatus.isEmpty() ? null : completionStatus;
            JsonNode payload = completionResult != null && !completionResult.isNull() ? completionResult : result;
            String given = status == null ? "" : status;
            return switch (given.toUpperCase(Locale.ROOT)) {
                case "" -> new Completion(InstanceStatus.COMPLETED, outcome, payload);
                case "SUCCESS", "FAILURE" -> new Completion(InstanceStatus.COMPLETED,
                        outcome != null ? outcome : given.toUpperCase(Locale.ROOT), payload);
                default -> new Completion(InstanceStatus.parseWire(given), outcome, payload);
            };
        }
    }
}

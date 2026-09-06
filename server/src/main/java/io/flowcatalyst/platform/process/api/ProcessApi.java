package io.flowcatalyst.platform.process.api;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.platform.process.ProcessRepository;
import io.flowcatalyst.platform.process.ProcessRepository.ListFilter;
import io.flowcatalyst.platform.process.operations.ArchiveCommand;
import io.flowcatalyst.platform.process.operations.ArchiveProcess;
import io.flowcatalyst.platform.process.operations.CreateCommand;
import io.flowcatalyst.platform.process.operations.CreateProcess;
import io.flowcatalyst.platform.process.operations.DeleteCommand;
import io.flowcatalyst.platform.process.operations.DeleteProcess;
import io.flowcatalyst.platform.process.operations.UpdateCommand;
import io.flowcatalyst.platform.process.operations.UpdateProcess;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/api/processes` surface (spec §3). A write handler does exactly:
/// coarse permission → command from DTO → `Operation.run` → response. Reads
/// go straight to the repository. Every handler runs inside [Auth#scoped] so
/// the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/processes` | 200 [ProcessListResponse] |
/// | POST | `/api/processes` | 201 [CreatedResponse] |
/// | GET | `/api/processes/by-code/{code}` | 200 [ProcessResponse] |
/// | GET | `/api/processes/{id}` | 200 [ProcessResponse] |
/// | PUT | `/api/processes/{id}` | 204 |
/// | POST | `/api/processes/{id}/archive` | 204 |
/// | DELETE | `/api/processes/{id}` | 204 |
///
/// The sync routes (`/api/applications/{appCode}/processes/sync`,
/// `/api/processes/sync`) belong to the sdksync surface; the operation lives
/// in this package, the routes do not.
public final class ProcessApi {

    private ProcessApi() {
    }

    /// The handlers' dependencies.
    public record State(ProcessRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints under `/api/processes`; paths, methods and status
    /// codes are the lockfile's.
    public static void register(Routes routes, State s) {
        registerAt(routes, "/api/processes", s);
    }

    /// Mounts every process route under `prefix` — `/api/processes` for the
    /// SDK surface, `/bff/processes` for the SPA (bff spec §8, Go
    /// `registerAt`): the two prefixes serve the same handlers.
    public static void registerAt(Routes routes, String prefix, State s) {
        routes.get(prefix, Auth.scoped(ctx -> list(ctx, s)));
        routes.post(prefix, Auth.scoped(ctx -> create(ctx, s)));
        routes.get(prefix + "/by-code/{code}", Auth.scoped(ctx -> getByCode(ctx, s)));
        routes.get(prefix + "/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.put(prefix + "/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.post(prefix + "/{id}/archive", Auth.scoped(ctx -> archive(ctx, s)));
        routes.delete(prefix + "/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.require(Auth.current(), PROCESS_VIEW);
        ctx.json(ProcessListResponse.from(s.repo().findWithFilters(listFilter(ctx))));
    }

    private static void create(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), PROCESS_CREATE, PROCESS_UPDATE, PROCESS_DELETE);
        var cmd = ctx.bodyAsClass(CreateProcessRequest.class).toCommand();
        var event = CreateProcess.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.processId()));
    }

    private static void getByCode(Exchange ctx, State s) {
        Checks.require(Auth.current(), PROCESS_VIEW);
        ctx.json(ProcessResponse.from(processByCode(s, ctx.pathParam("code"))));
    }

    private static void getById(Exchange ctx, State s) {
        Checks.require(Auth.current(), PROCESS_VIEW);
        ctx.json(ProcessResponse.from(process(s, ctx.pathParam("id"))));
    }

    private static void update(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), PROCESS_CREATE, PROCESS_UPDATE, PROCESS_DELETE);
        var cmd = ctx.bodyAsClass(UpdateProcessRequest.class).toCommand(ctx.pathParam("id"));
        UpdateProcess.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void archive(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), PROCESS_CREATE, PROCESS_UPDATE, PROCESS_DELETE);
        ArchiveProcess.of(s.repo()).run(s.uow(), new ArchiveCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), PROCESS_DELETE);
        DeleteProcess.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter; there is no implied default (spec §3).
    private static ListFilter listFilter(Exchange ctx) {
        return new ListFilter(queryParam(ctx, "application"), queryParam(ctx, "subdomain"), queryParam(ctx, "status"));
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Process process(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("Process", id));
    }

    private static Process processByCode(State s, String code) {
        return s.repo().findByCode(code).orElseThrow(() -> HttpError.notFound("Process", code));
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/processes`.
    public record CreateProcessRequest(String code, String name, String description, String body, String diagramType,
                                       List<String> tags) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, description, body, diagramType, tags);
        }
    }

    /// Body of `PUT /api/processes/{id}`; every field optional, absent = untouched.
    public record UpdateProcessRequest(String name, String description, String body, String diagramType,
                                       List<String> tags) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description, body, diagramType, tags);
        }
    }

    /// The wire shape of one process; optional fields (`description`,
    /// `createdBy`) are omitted when `null`; `body` and `tags` are always present.
    public record ProcessResponse(
            String id,
            String code,
            String name,
            String description,
            String status,
            String source,
            String application,
            String subdomain,
            String processName,
            String body,
            String diagramType,
            List<String> tags,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public static ProcessResponse from(Process p) {
            return new ProcessResponse(p.id(), p.code(), p.name(), p.description(), p.status().name(), p.source().name(),
                    p.application(), p.subdomain(), p.processName(), p.body(), p.diagramType(), p.tags(),
                    p.createdBy(), p.createdAt(), p.updatedAt());
        }
    }

    /// `{"items": [...]}` — no pagination on this endpoint.
    public record ProcessListResponse(List<ProcessResponse> items) {
        public ProcessListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static ProcessListResponse from(List<Process> processes) {
            return new ProcessListResponse(processes.stream().map(ProcessResponse::from).toList());
        }
    }
}

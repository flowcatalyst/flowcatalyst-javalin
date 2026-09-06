package io.flowcatalyst.platform.dispatchpool.api;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository.ListFilter;
import io.flowcatalyst.platform.dispatchpool.operations.ActivateCommand;
import io.flowcatalyst.platform.dispatchpool.operations.ActivateDispatchPool;
import io.flowcatalyst.platform.dispatchpool.operations.ArchiveCommand;
import io.flowcatalyst.platform.dispatchpool.operations.ArchiveDispatchPool;
import io.flowcatalyst.platform.dispatchpool.operations.CreateCommand;
import io.flowcatalyst.platform.dispatchpool.operations.CreateDispatchPool;
import io.flowcatalyst.platform.dispatchpool.operations.DeleteCommand;
import io.flowcatalyst.platform.dispatchpool.operations.DeleteDispatchPool;
import io.flowcatalyst.platform.dispatchpool.operations.SuspendCommand;
import io.flowcatalyst.platform.dispatchpool.operations.SuspendDispatchPool;
import io.flowcatalyst.platform.dispatchpool.operations.UpdateCommand;
import io.flowcatalyst.platform.dispatchpool.operations.UpdateDispatchPool;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/api/dispatch-pools` surface (spec §3). A write handler does exactly:
/// coarse permission → command from DTO → `Operation.run` → response. Reads
/// go straight to the repository and apply the client-scope visibility rule
/// here. Every handler runs inside [Auth#scoped] so the operations can read
/// [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/dispatch-pools` | 200 [DispatchPoolListResponse] |
/// | POST | `/api/dispatch-pools` | 201 [CreatedResponse] |
/// | GET | `/api/dispatch-pools/{id}` | 200 [DispatchPoolResponse] |
/// | PUT | `/api/dispatch-pools/{id}` | 204 |
/// | POST | `/api/dispatch-pools/{id}/archive` | 204 |
/// | POST | `/api/dispatch-pools/{id}/suspend` | 204 |
/// | POST | `/api/dispatch-pools/{id}/activate` | 204 |
/// | DELETE | `/api/dispatch-pools/{id}` | 204 |
public final class DispatchPoolApi {

    private DispatchPoolApi() {
    }

    /// The handlers' dependencies.
    public record State(DispatchPoolRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(Routes routes, State s) {
        routes.get("/api/dispatch-pools", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/dispatch-pools", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/dispatch-pools/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.put("/api/dispatch-pools/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.post("/api/dispatch-pools/{id}/archive", Auth.scoped(ctx -> archive(ctx, s)));
        routes.post("/api/dispatch-pools/{id}/suspend", Auth.scoped(ctx -> suspend(ctx, s)));
        routes.post("/api/dispatch-pools/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
        routes.delete("/api/dispatch-pools/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, DISPATCH_POOL_VIEW);
        List<DispatchPool> visible = Checks.filterClientScoped(ac, s.repo().findWithFilters(listFilter(ctx)), DispatchPool::clientId);
        ctx.json(DispatchPoolListResponse.from(visible));
    }

    private static void getById(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, DISPATCH_POOL_VIEW);
        String id = ctx.pathParam("id");
        ctx.json(DispatchPoolResponse.from(visible(ac, s.repo().findById(id).orElseThrow(() -> HttpError.notFound("DispatchPool", id)))));
    }

    private static void create(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), DISPATCH_POOL_CREATE, DISPATCH_POOL_UPDATE, DISPATCH_POOL_DELETE);
        var cmd = ctx.bodyAsClass(CreateDispatchPoolRequest.class).toCommand();
        var event = CreateDispatchPool.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.poolId()));
    }

    private static void update(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), DISPATCH_POOL_CREATE, DISPATCH_POOL_UPDATE, DISPATCH_POOL_DELETE);
        var cmd = ctx.bodyAsClass(UpdateDispatchPoolRequest.class).toCommand(ctx.pathParam("id"));
        UpdateDispatchPool.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void archive(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), DISPATCH_POOL_CREATE, DISPATCH_POOL_UPDATE, DISPATCH_POOL_DELETE);
        ArchiveDispatchPool.of(s.repo()).run(s.uow(), new ArchiveCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void suspend(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), DISPATCH_POOL_CREATE, DISPATCH_POOL_UPDATE, DISPATCH_POOL_DELETE);
        SuspendDispatchPool.of(s.repo()).run(s.uow(), new SuspendCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void activate(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), DISPATCH_POOL_CREATE, DISPATCH_POOL_UPDATE, DISPATCH_POOL_DELETE);
        ActivateDispatchPool.of(s.repo()).run(s.uow(), new ActivateCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), DISPATCH_POOL_DELETE);
        DeleteDispatchPool.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter; an absent or empty value is no filter. There is
    /// no default status: archived pools are listed unless `status` says otherwise.
    private static ListFilter listFilter(Exchange ctx) {
        return new ListFilter(queryParam(ctx, "status"), queryParam(ctx, "clientId"));
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /// A client-bound pool is visible only to principals with access to that client.
    private static DispatchPool visible(AuthContext ac, DispatchPool p) {
        if (p.clientId() != null && !ac.canAccessClient(p.clientId())) {
            throw HttpError.forbidden("No access to this dispatch pool");
        }
        return p;
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/dispatch-pools`.
    public record CreateDispatchPoolRequest(String code, String name, String description, Integer rateLimit,
                                            Integer concurrency, String clientId) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, description, rateLimit, concurrency, clientId);
        }
    }

    /// Body of `PUT /api/dispatch-pools/{id}`; every field optional (absent = unchanged).
    public record UpdateDispatchPoolRequest(String name, String description, Integer rateLimit, Integer concurrency) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description, rateLimit, concurrency);
        }
    }

    /// The wire shape of one pool; optional fields (`description`, `rateLimit`,
    /// `clientId`, `clientIdentifier`) are omitted when `null`.
    public record DispatchPoolResponse(
            String id,
            String code,
            String name,
            String description,
            Integer rateLimit,
            int concurrency,
            String clientId,
            String clientIdentifier,
            String status,
            Instant createdAt,
            Instant updatedAt) {

        public static DispatchPoolResponse from(DispatchPool p) {
            return new DispatchPoolResponse(p.id(), p.code(), p.name(), p.description(), p.rateLimit(), p.concurrency(),
                    p.clientId(), p.clientIdentifier(), p.status().name(), p.createdAt(), p.updatedAt());
        }
    }

    /// `{"pools": [...], "total": n}` — `total` is the visible count (spec §3).
    public record DispatchPoolListResponse(List<DispatchPoolResponse> pools, long total) {
        public DispatchPoolListResponse {
            pools = pools == null ? List.of() : List.copyOf(pools);
        }

        public static DispatchPoolListResponse from(List<DispatchPool> visible) {
            var pools = visible.stream().map(DispatchPoolResponse::from).toList();
            return new DispatchPoolListResponse(pools, pools.size());
        }
    }
}

package io.flowcatalyst.platform.cors.api;

import io.flowcatalyst.platform.cors.CorsOrigin;
import io.flowcatalyst.platform.cors.CorsOriginRepository;
import io.flowcatalyst.platform.cors.operations.AddCommand;
import io.flowcatalyst.platform.cors.operations.AddOrigin;
import io.flowcatalyst.platform.cors.operations.DeleteCommand;
import io.flowcatalyst.platform.cors.operations.DeleteOrigin;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The `/api/platform/cors` surface (spec §3). The allowlist is anchor-only:
/// every handler opens with `requireAnchor` — except `/allowed`, the public,
/// browser-facing read that serves the origin strings to a caller that is
/// not logged in yet. A write handler does exactly: gate → command from DTO →
/// `Operation.run` → response. Reads go straight to the repository. Every
/// handler runs inside [Auth#scoped] so the operations can read
/// [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/platform/cors/allowed` | 200 [PublicAllowedResponse] (public) |
/// | GET | `/api/platform/cors` | 200 [CorsOriginListResponse] |
/// | POST | `/api/platform/cors` | 201 [CreatedResponse] |
/// | GET | `/api/platform/cors/{id}` | 200 [AllowedOriginResponse] |
/// | DELETE | `/api/platform/cors/{id}` | 204 |
public final class CorsOriginApi {

    private CorsOriginApi() {
    }

    /// The handlers' dependencies. `onChange` runs after every successful
    /// add/delete (spec §9: the filter's [io.flowcatalyst.platform.cors.filter.CorsAllowlist]
    /// invalidates its cache on the signal) — a no-op `Runnable` in tests
    /// that do not care.
    public record State(CorsOriginRepository repo, UnitOfWork uow, Runnable onChange) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(onChange, "onChange");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the
    /// lockfile's. The literal `/allowed` segment is registered before the
    /// `{id}` routes so it takes precedence (spec §3).
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/platform/cors/allowed", Auth.scoped(ctx -> publicAllowed(ctx, s)));
        routes.get("/api/platform/cors", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/platform/cors", Auth.scoped(ctx -> add(ctx, s)));
        routes.get("/api/platform/cors/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.delete("/api/platform/cors/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    /// Public by spec: no gate, the principal is never consulted.
    private static void publicAllowed(Context ctx, State s) {
        ctx.json(new PublicAllowedResponse(s.repo().allowedOrigins()));
    }

    private static void list(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        ctx.json(CorsOriginListResponse.from(s.repo().findAll()));
    }

    private static void getById(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        ctx.json(AllowedOriginResponse.from(load(s, ctx.pathParam("id"))));
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    private static void add(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(AddOriginRequest.class).toCommand();
        var event = AddOrigin.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        s.onChange().run();
        ctx.status(201).json(new CreatedResponse(event.originId()));
    }

    private static void delete(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        DeleteOrigin.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        s.onChange().run();
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static CorsOrigin load(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("CorsOrigin", id));
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/platform/cors`.
    public record AddOriginRequest(String origin, String description) {
        public AddCommand toCommand() {
            return new AddCommand(origin, description);
        }
    }

    /// The wire shape of one allowlist entry; `description` and `createdBy`
    /// are omitted when `null`.
    public record AllowedOriginResponse(
            String id,
            String origin,
            String description,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public static AllowedOriginResponse from(CorsOrigin o) {
            return new AllowedOriginResponse(o.id(), o.origin(), o.description(), o.createdBy(), o.createdAt(), o.updatedAt());
        }
    }

    /// `{"corsOrigins": [...], "total": n}` — `total` is the list size (no pagination).
    public record CorsOriginListResponse(List<AllowedOriginResponse> corsOrigins, int total) {
        public CorsOriginListResponse {
            corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
        }

        public static CorsOriginListResponse from(List<CorsOrigin> origins) {
            var items = origins.stream().map(AllowedOriginResponse::from).toList();
            return new CorsOriginListResponse(items, items.size());
        }
    }

    /// `{"origins": [...]}` — the browser-facing allowlist, origin strings only.
    public record PublicAllowedResponse(List<String> origins) {
        public PublicAllowedResponse {
            origins = origins == null ? List.of() : List.copyOf(origins);
        }
    }
}

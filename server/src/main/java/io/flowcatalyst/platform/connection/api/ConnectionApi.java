package io.flowcatalyst.platform.connection.api;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.ConnectionRepository.ListFilter;
import io.flowcatalyst.platform.connection.operations.ActivateCommand;
import io.flowcatalyst.platform.connection.operations.ActivateConnection;
import io.flowcatalyst.platform.connection.operations.CreateCommand;
import io.flowcatalyst.platform.connection.operations.CreateConnection;
import io.flowcatalyst.platform.connection.operations.DeleteCommand;
import io.flowcatalyst.platform.connection.operations.DeleteConnection;
import io.flowcatalyst.platform.connection.operations.PauseCommand;
import io.flowcatalyst.platform.connection.operations.PauseConnection;
import io.flowcatalyst.platform.connection.operations.UpdateCommand;
import io.flowcatalyst.platform.connection.operations.UpdateConnection;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/api/connections` surface (spec §3). A write handler does exactly:
/// coarse permission → command from DTO → `Operation.run` → response. Reads
/// go straight to the repository and apply the client-scope visibility rule
/// here. Every handler runs inside [Auth#scoped] so the operations can read
/// [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/connections` | 200 [ConnectionListResponse] |
/// | POST | `/api/connections` | 201 [ConnectionResponse] |
/// | GET | `/api/connections/{id}` | 200 [ConnectionResponse] |
/// | PUT | `/api/connections/{id}` | 204 |
/// | DELETE | `/api/connections/{id}` | 204 |
/// | POST | `/api/connections/{id}/pause` | 200 [ConnectionResponse] |
/// | POST | `/api/connections/{id}/activate` | 200 [ConnectionResponse] |
public final class ConnectionApi {

    private ConnectionApi() {
    }

    /// The handlers' dependencies.
    public record State(ConnectionRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/connections", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/connections", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/connections/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.put("/api/connections/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.delete("/api/connections/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.post("/api/connections/{id}/pause", Auth.scoped(ctx -> pause(ctx, s)));
        routes.post("/api/connections/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, CONNECTION_VIEW);
        List<Connection> visible = Checks.filterClientScoped(ac, s.repo().findWithFilters(listFilter(ctx)), Connection::clientId);
        ctx.json(ConnectionListResponse.from(visible));
    }

    private static void getById(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, CONNECTION_VIEW);
        ctx.json(ConnectionResponse.from(visible(ac, load(s, ctx.pathParam("id")))));
    }

    /// Answers with the full connection (re-read after the write), as the
    /// lockfile says — the SPA pushes it straight into a select (spec §3).
    private static void create(Context ctx, State s) {
        Checks.require(Auth.current(), CONNECTION_CREATE);
        var cmd = ctx.bodyAsClass(CreateConnectionRequest.class).toCommand();
        var event = CreateConnection.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(ConnectionResponse.from(load(s, event.connectionId())));
    }

    private static void update(Context ctx, State s) {
        Checks.require(Auth.current(), CONNECTION_UPDATE);
        var cmd = ctx.bodyAsClass(UpdateConnectionRequest.class).toCommand(ctx.pathParam("id"));
        UpdateConnection.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Context ctx, State s) {
        Checks.require(Auth.current(), CONNECTION_DELETE);
        DeleteConnection.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void pause(Context ctx, State s) {
        Checks.require(Auth.current(), CONNECTION_UPDATE);
        String id = ctx.pathParam("id");
        PauseConnection.of(s.repo()).run(s.uow(), new PauseCommand(id), Auth.executionContext());
        ctx.json(ConnectionResponse.from(load(s, id)));
    }

    private static void activate(Context ctx, State s) {
        Checks.require(Auth.current(), CONNECTION_UPDATE);
        String id = ctx.pathParam("id");
        ActivateConnection.of(s.repo()).run(s.uow(), new ActivateCommand(id), Auth.executionContext());
        ctx.json(ConnectionResponse.from(load(s, id)));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter; both are plain equality filters, no defaults.
    private static ListFilter listFilter(Context ctx) {
        return new ListFilter(queryParam(ctx, "status"), queryParam(ctx, "clientId"));
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Connection load(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("Connection", id));
    }

    /// A client-scoped connection is visible only to principals with access to that client.
    private static Connection visible(AuthContext ac, Connection c) {
        if (c.clientId() != null && !ac.canAccessClient(c.clientId())) {
            throw HttpError.forbidden("No access to this connection");
        }
        return c;
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/connections`.
    public record CreateConnectionRequest(String code, String name, String description, String serviceAccountId,
                                          String externalId, String clientId) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, description, serviceAccountId, externalId, clientId);
        }
    }

    /// Body of `PUT /api/connections/{id}`; the path id is authoritative.
    public record UpdateConnectionRequest(String name, String description, String externalId, String status) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description, externalId, status);
        }
    }

    /// The wire shape of one connection; optional fields (`description`,
    /// `externalId`, `clientId`, `clientIdentifier`) are omitted when `null`.
    public record ConnectionResponse(
            String id,
            String code,
            String name,
            String description,
            String externalId,
            String status,
            String serviceAccountId,
            String clientId,
            String clientIdentifier,
            Instant createdAt,
            Instant updatedAt) {

        public static ConnectionResponse from(Connection c) {
            return new ConnectionResponse(c.id(), c.code(), c.name(), c.description(), c.externalId(),
                    c.status().name(), c.serviceAccountId(), c.clientId(), c.clientIdentifier(),
                    c.createdAt(), c.updatedAt());
        }
    }

    /// `{"connections": [...], "total": n}` — `total` is the size of the
    /// visible list; no pagination on this endpoint.
    public record ConnectionListResponse(List<ConnectionResponse> connections, int total) {
        public ConnectionListResponse {
            connections = connections == null ? List.of() : List.copyOf(connections);
        }

        public static ConnectionListResponse from(List<Connection> visible) {
            var items = visible.stream().map(ConnectionResponse::from).toList();
            return new ConnectionListResponse(items, items.size());
        }
    }
}

package io.flowcatalyst.platform.role.api;

import io.flowcatalyst.platform.role.Permission;
import io.flowcatalyst.platform.role.PermissionRepository;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.RoleSource;
import io.flowcatalyst.platform.role.operations.CreateCommand;
import io.flowcatalyst.platform.role.operations.CreateRole;
import io.flowcatalyst.platform.role.operations.DeleteCommand;
import io.flowcatalyst.platform.role.operations.DeleteRole;
import io.flowcatalyst.platform.role.operations.GrantPermission;
import io.flowcatalyst.platform.role.operations.GrantPermissionCommand;
import io.flowcatalyst.platform.role.operations.RevokePermission;
import io.flowcatalyst.platform.role.operations.RevokePermissionCommand;
import io.flowcatalyst.platform.role.operations.UpdateCommand;
import io.flowcatalyst.platform.role.operations.UpdateRole;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/api/roles` surface (spec §3): the role CRUD, the per-role
/// permission grants and the permission catalogue. A write handler does
/// exactly: coarse permission → command from DTO → `Operation.run` →
/// response. Reads go straight to the repositories. Every handler runs
/// inside [Auth#scoped] so the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/roles` | 200 [RoleListResponse] |
/// | POST | `/api/roles` | 201 [CreatedResponse] |
/// | GET | `/api/roles/permissions` | 200 [PermissionListResponse] |
/// | GET | `/api/roles/permissions/{permission}` | 200 [PermissionResponse] |
/// | DELETE | `/api/roles/permissions/{permission}` | 204 |
/// | GET | `/api/roles/by-code/{code}` | 200 [RoleResponse] |
/// | GET | `/api/roles/by-source/{source}` | 200 `[RoleResponse]` |
/// | GET | `/api/roles/by-application/{applicationId}` | 200 `[RoleResponse]` |
/// | GET | `/api/roles/filters/applications` | 200 [ApplicationFilterListResponse] |
/// | GET | `/api/roles/{id}` | 200 [RoleResponse] |
/// | PUT | `/api/roles/{id}` | 204 |
/// | DELETE | `/api/roles/{id}` | 204 |
/// | GET | `/api/roles/{roleName}/permissions` | 200 [RolePermissionListResponse] |
/// | POST | `/api/roles/{roleName}/permissions` | 200 [RoleResponse] (SDK alias, permission in body) |
/// | POST | `/api/roles/{roleName}/permissions/{permission}` | 200 [RoleResponse] |
/// | DELETE | `/api/roles/{roleName}/permissions/{permission}` | 200 [RoleResponse] |
public final class RoleApi {

    private RoleApi() {
    }

    /// The handlers' dependencies.
    public record State(RoleRepository roles, PermissionRepository permissions, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(roles, "roles");
            Objects.requireNonNull(permissions, "permissions");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the
    /// lockfile's. Literal segments are registered before the `{id}` /
    /// `{roleName}` routes so they take precedence (spec §3).
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/roles", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/roles", Auth.scoped(ctx -> create(ctx, s)));
        // Permission catalogue.
        routes.get("/api/roles/permissions", Auth.scoped(ctx -> listPermissions(ctx, s)));
        routes.get("/api/roles/permissions/{permission}", Auth.scoped(ctx -> getPermission(ctx, s)));
        routes.delete("/api/roles/permissions/{permission}", Auth.scoped(ctx -> deletePermission(ctx, s)));
        // Lookups by code / source / application + filter options.
        routes.get("/api/roles/by-code/{code}", Auth.scoped(ctx -> getByCode(ctx, s)));
        routes.get("/api/roles/by-source/{source}", Auth.scoped(ctx -> listBySource(ctx, s)));
        routes.get("/api/roles/by-application/{applicationId}", Auth.scoped(ctx -> listByApplication(ctx, s)));
        routes.get("/api/roles/filters/applications", Auth.scoped(ctx -> applicationFilters(ctx, s)));
        // The role itself.
        routes.get("/api/roles/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.put("/api/roles/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.delete("/api/roles/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        // Permission grants on a role.
        routes.get("/api/roles/{roleName}/permissions", Auth.scoped(ctx -> listRolePermissions(ctx, s)));
        routes.post("/api/roles/{roleName}/permissions", Auth.scoped(ctx -> grantFromBody(ctx, s))); // SDK alias
        routes.post("/api/roles/{roleName}/permissions/{permission}", Auth.scoped(ctx -> grant(ctx, s)));
        routes.delete("/api/roles/{roleName}/permissions/{permission}", Auth.scoped(ctx -> revoke(ctx, s)));
    }

    // ── Role handlers ──────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(RoleListResponse.from(s.roles().findAll()));
    }

    private static void create(Context ctx, State s) {
        Checks.requireAny(Auth.current(), ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE);
        var cmd = ctx.bodyAsClass(CreateRoleRequest.class).toCommand();
        var event = CreateRole.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.roleId()));
    }

    private static void getById(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(RoleResponse.from(resolveRole(s, ctx.pathParam("id"))));
    }

    private static void update(Context ctx, State s) {
        Checks.requireAny(Auth.current(), ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE);
        var cmd = ctx.bodyAsClass(UpdateRoleRequest.class).toCommand(resolveRole(s, ctx.pathParam("id")).id());
        UpdateRole.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_DELETE);
        var cmd = new DeleteCommand(resolveRole(s, ctx.pathParam("id")).id());
        DeleteRole.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void getByCode(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(RoleResponse.from(roleNamed(s, ctx.pathParam("code"))));
    }

    /// Bare JSON array; an unknown source segment is 400 `INVALID_SOURCE` — Go's
    /// rule (`api.go` bySource), adopted 2026-09-05 when the parity harness
    /// showed Java's earlier leniency (spec §3, open question 5, now ruled).
    private static void listBySource(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        RoleSource source;
        try {
            source = RoleSource.parse(ctx.pathParam("source"));
        } catch (RoleSource.UnrecognisedRoleSourceException e) {
            throw UseCaseException.validation("INVALID_SOURCE", "source must be CODE, DATABASE, or SDK");
        }
        ctx.json(RoleResponse.from(s.roles().findBySource(source)));
    }

    /// Bare JSON array.
    private static void listByApplication(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(RoleResponse.from(s.roles().findByApplicationId(ctx.pathParam("applicationId"))));
    }

    private static void applicationFilters(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(new ApplicationFilterListResponse(s.roles().applicationCodes()));
    }

    // ── Permission grants on a role (addressed by name) ────────────────────

    private static void listRolePermissions(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(new RolePermissionListResponse(roleNamed(s, ctx.pathParam("roleName")).permissions()));
    }

    private static void grant(Context ctx, State s) {
        Checks.requireAny(Auth.current(), ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE);
        var cmd = new GrantPermissionCommand(ctx.pathParam("roleName"), ctx.pathParam("permission"));
        GrantPermission.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(RoleResponse.from(roleNamed(s, cmd.roleName())));
    }

    /// The SDK shape: `{permission}` in the body — the same grant operation.
    private static void grantFromBody(Context ctx, State s) {
        Checks.requireAny(Auth.current(), ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE);
        var cmd = ctx.bodyAsClass(GrantPermissionRequest.class).toCommand(ctx.pathParam("roleName"));
        GrantPermission.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(RoleResponse.from(roleNamed(s, cmd.roleName())));
    }

    private static void revoke(Context ctx, State s) {
        Checks.requireAny(Auth.current(), ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE);
        var cmd = new RevokePermissionCommand(ctx.pathParam("roleName"), ctx.pathParam("permission"));
        RevokePermission.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(RoleResponse.from(roleNamed(s, cmd.roleName())));
    }

    // ── Permission catalogue ───────────────────────────────────────────────

    private static void listPermissions(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(PermissionListResponse.from(s.permissions().findAll()));
    }

    private static void getPermission(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        String code = ctx.pathParam("permission");
        ctx.json(PermissionResponse.from(s.permissions().findByCode(code).orElseThrow(() -> HttpError.notFound("Permission", code))));
    }

    /// A direct, idempotent catalogue delete — no domain event, no audit row
    /// (spec §3, open question 4); committed through the unit of work so the
    /// write still goes through one transaction.
    private static void deletePermission(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_DELETE);
        String code = ctx.pathParam("permission");
        s.uow().inTransaction(tx -> {
            s.permissions().deleteByCode(code, tx.dbTx());
            return null;
        });
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// The SPA addresses roles by TSID, the SDKs by name on the same
    /// `{id}` routes; TSIDs never contain `:` and names always do, so the
    /// fallback is unambiguous.
    private static Role resolveRole(State s, String idOrName) {
        return s.roles().findById(idOrName)
                .or(() -> s.roles().findByName(idOrName))
                .orElseThrow(() -> HttpError.notFound("Role", idOrName));
    }

    /// The name-only routes (`by-code`, `{roleName}/permissions…`): no id fallback.
    private static Role roleNamed(State s, String name) {
        return s.roles().findByName(name).orElseThrow(() -> HttpError.notFound("Role", name));
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/roles`.
    public record CreateRoleRequest(String applicationCode, String roleName, String displayName, String description,
                                    List<String> permissions, boolean clientManaged) {
        public CreateCommand toCommand() {
            return new CreateCommand(applicationCode, roleName, displayName, description, permissions, clientManaged);
        }
    }

    /// Body of `PUT /api/roles/{id}`; every field optional, absent = untouched.
    public record UpdateRoleRequest(String displayName, String description, List<String> permissions, Boolean clientManaged) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, displayName, description, permissions, clientManaged);
        }
    }

    /// Body of `POST /api/roles/{roleName}/permissions`.
    public record GrantPermissionRequest(String permission) {
        public GrantPermissionCommand toCommand(String roleName) {
            return new GrantPermissionCommand(roleName, permission);
        }
    }

    /// The wire shape of one role; optional fields (`applicationId`,
    /// `description`) are omitted when `null`; `applicationCode` is required
    /// on the wire and renders `""` for a row without one.
    public record RoleResponse(
            String id,
            String applicationId,
            String name,
            String displayName,
            String description,
            String applicationCode,
            List<String> permissions,
            String source,
            boolean clientManaged,
            Instant createdAt,
            Instant updatedAt) {

        public static RoleResponse from(Role r) {
            return new RoleResponse(r.id(), r.applicationId(), r.name(), r.displayName(), r.description(),
                    r.applicationCode() == null ? "" : r.applicationCode(), r.permissions(), r.source().name(),
                    r.clientManaged(), r.createdAt(), r.updatedAt());
        }

        public static List<RoleResponse> from(List<Role> roles) {
            return roles.stream().map(RoleResponse::from).toList();
        }
    }

    /// `{"roles": [...], "total": n}` — no pagination.
    public record RoleListResponse(List<RoleResponse> roles, int total) {
        public RoleListResponse {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public static RoleListResponse from(List<Role> roles) {
            var out = RoleResponse.from(roles);
            return new RoleListResponse(out, out.size());
        }
    }

    /// `{"permissions": [...]}` — one role's codes.
    public record RolePermissionListResponse(List<String> permissions) {
        public RolePermissionListResponse {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    /// `{"applicationCodes": [...]}`.
    public record ApplicationFilterListResponse(List<String> applicationCodes) {
        public ApplicationFilterListResponse {
            applicationCodes = applicationCodes == null ? List.of() : List.copyOf(applicationCodes);
        }
    }

    /// One catalogue entry: `name` is the code (the catalogue has no display
    /// name), `category` is `subdomain:context:aggregate`.
    public record PermissionResponse(String permission, String name, String description, String category) {
        public static PermissionResponse from(Permission p) {
            return new PermissionResponse(p.code(), p.code(), p.description(), p.category());
        }
    }

    /// `{"permissions": [...], "total": n}`.
    public record PermissionListResponse(List<PermissionResponse> permissions, int total) {
        public PermissionListResponse {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }

        public static PermissionListResponse from(List<Permission> rows) {
            var out = rows.stream().map(PermissionResponse::from).toList();
            return new PermissionListResponse(out, out.size());
        }
    }
}

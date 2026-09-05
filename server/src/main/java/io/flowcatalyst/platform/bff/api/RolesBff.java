package io.flowcatalyst.platform.bff.api;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.role.Permission;
import io.flowcatalyst.platform.role.PermissionRepository;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.operations.CreateCommand;
import io.flowcatalyst.platform.role.operations.CreateRole;
import io.flowcatalyst.platform.role.operations.DeleteCommand;
import io.flowcatalyst.platform.role.operations.DeleteRole;
import io.flowcatalyst.platform.role.operations.SyncPlatformRoles;
import io.flowcatalyst.platform.role.operations.SyncPlatformRolesCommand;
import io.flowcatalyst.platform.role.operations.UpdateCommand;
import io.flowcatalyst.platform.role.operations.UpdateRole;
import io.flowcatalyst.platform.seed.PlatformRoles;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.ROLE_VIEW;

/// The `/bff/roles` surface (bff spec §6): the SPA's own role and
/// permission-catalogue shapes, reusing
/// [io.flowcatalyst.platform.role.operations] verbatim. Writes on the role
/// itself (create/update/delete/sync-platform) and the permission catalogue
/// are anchor-only (bff spec §6) — a stricter gate than `/api/roles`, which
/// this class does not touch.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/bff/roles` | 200 [RoleListResponse] |
/// | GET | `/bff/roles/filters/applications` | 200 [ApplicationOptionsResponse] |
/// | GET | `/bff/roles/permissions` | 200 [PermissionListResponse] |
/// | POST | `/bff/roles/permissions` | 201 [PermissionResponse]; anchor-only |
/// | GET | `/bff/roles/permissions/{permission}` | 200 [PermissionResponse] |
/// | GET | `/bff/roles/{roleName}` | 200 [RoleResponse] |
/// | POST | `/bff/roles` | 201 [CreatedResponse]; anchor-only |
/// | PUT | `/bff/roles/{roleName}` | 204; anchor-only |
/// | DELETE | `/bff/roles/{roleName}` | 204; anchor-only |
/// | POST | `/bff/roles/sync-platform` | 200 [SyncPlatformResponse]; anchor-only |
public final class RolesBff {

    private RolesBff() {
    }

    public record State(RoleRepository roles, PermissionRepository permissions, ApplicationRepository applications,
                        UnitOfWork uow) {
        public State {
            Objects.requireNonNull(roles, "roles");
            Objects.requireNonNull(permissions, "permissions");
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(uow, "uow");
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/bff/roles", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/bff/roles", Auth.scoped(ctx -> create(ctx, s)));
        routes.post("/bff/roles/sync-platform", Auth.scoped(ctx -> syncPlatform(ctx, s)));
        routes.get("/bff/roles/filters/applications", Auth.scoped(ctx -> filterApplications(ctx, s)));
        routes.get("/bff/roles/permissions", Auth.scoped(ctx -> listPermissions(ctx, s)));
        routes.post("/bff/roles/permissions", Auth.scoped(ctx -> createPermission(ctx, s)));
        routes.get("/bff/roles/permissions/{permission}", Auth.scoped(ctx -> getPermission(ctx, s)));
        routes.get("/bff/roles/{roleName}", Auth.scoped(ctx -> getByName(ctx, s)));
        routes.put("/bff/roles/{roleName}", Auth.scoped(ctx -> update(ctx, s)));
        routes.delete("/bff/roles/{roleName}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Role handlers ──────────────────────────────────────────────────────

    /// `findAll`, filtered in memory by `application` / `source` (bff spec
    /// §6 D3: Java may filter in SQL — kept in-memory here, matching the
    /// aggregate's own read side, since the row counts are the same ones
    /// `/api/roles` already reads unfiltered).
    private static void list(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        String application = queryParam(ctx, "application");
        String source = queryParam(ctx, "source");
        List<Role> rows = s.roles().findAll().stream()
                .filter(r -> application == null || application.equals(r.applicationCode()))
                .filter(r -> source == null || source.equalsIgnoreCase(r.source().name()))
                .toList();
        ctx.json(RoleListResponse.from(rows));
    }

    private static void getByName(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        ctx.json(RoleResponse.from(roleNamed(s, ctx.pathParam("roleName"))));
    }

    private static void create(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(CreateRoleRequest.class).toCommand();
        var event = CreateRole.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.roleId()));
    }

    private static void update(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(UpdateRoleRequest.class).toCommand(roleNamed(s, ctx.pathParam("roleName")).id());
        UpdateRole.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = new DeleteCommand(roleNamed(s, ctx.pathParam("roleName")).id());
        DeleteRole.of(s.roles()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void filterApplications(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        List<ApplicationOption> options = s.roles().applicationCodes().stream()
                .map(code -> s.applications().findByCode(code)
                        .map(a -> new ApplicationOption(a.id(), a.code(), a.name()))
                        .orElseGet(() -> new ApplicationOption(code, code, code)))
                .toList();
        ctx.json(new ApplicationOptionsResponse(options));
    }

    /// Anchor-only: upserts the built-in role catalogue (`seed.PlatformRoles`).
    private static void syncPlatform(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var event = SyncPlatformRoles.of(s.roles(), PlatformRoles.all()).run(s.uow(), new SyncPlatformRolesCommand(), Auth.executionContext());
        ctx.json(new SyncPlatformResponse(event.created(), event.updated(), event.removed(), event.total()));
    }

    // ── Permission catalogue handlers ───────────────────────────────────────

    /// The seeded platform permissions ∪ `iam_permissions` rows, filtered by
    /// application and deduplicated by code (bff spec §6): seeded entries win
    /// a code collision (a catalogue row never overrides a builtin's
    /// description with a blank one).
    private static void listPermissions(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        String application = queryParam(ctx, "application");
        ctx.json(PermissionListResponse.from(catalogue(s, application)));
    }

    private static void createPermission(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var req = ctx.bodyAsClass(CreatePermissionRequest.class);
        String code = req.application() + ":" + req.context() + ":" + req.aggregate() + ":" + req.action();
        Permission p = Permission.define(code, req.description());
        s.uow().inTransaction(tx -> {
            s.permissions().upsert(p, tx.dbTx());
            return null;
        });
        ctx.status(201).json(PermissionResponse.from(p));
    }

    private static void getPermission(Context ctx, State s) {
        Checks.require(Auth.current(), ROLE_VIEW);
        String code = ctx.pathParam("permission");
        Permission p = catalogue(s, null).stream().filter(e -> e.code().equals(code)).findFirst()
                .orElseThrow(() -> HttpError.notFound("Permission", code));
        ctx.json(PermissionResponse.from(p));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static Role roleNamed(State s, String name) {
        return s.roles().findByName(name).orElseThrow(() -> HttpError.notFound("Role", name));
    }

    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /// The merged, deduplicated-by-code catalogue: every
    /// [io.flowcatalyst.platform.shared.auth.Permission] constant, then every
    /// `iam_permissions` row not already present, filtered to `application`
    /// (the code's first segment) when given.
    private static List<Permission> catalogue(State s, String application) {
        Map<String, Permission> byCode = new LinkedHashMap<>();
        for (var seeded : io.flowcatalyst.platform.shared.auth.Permission.values()) {
            byCode.put(seeded.code(), Permission.define(seeded.code(), null));
        }
        for (Permission stored : s.permissions().findAll()) {
            byCode.putIfAbsent(stored.code(), stored);
        }
        return byCode.values().stream()
                .filter(p -> application == null || application.equals(p.subdomain()))
                .sorted(Comparator.comparing(Permission::code))
                .toList();
    }

    // ── Wire DTOs (SPA shape, bff spec §6) ──────────────────────────────────

    public record CreateRoleRequest(String applicationCode, String roleName, String displayName, String description,
                                    List<String> permissions, boolean clientManaged) {
        public CreateCommand toCommand() {
            return new CreateCommand(applicationCode, roleName, displayName, description, permissions, clientManaged);
        }
    }

    public record UpdateRoleRequest(String displayName, String description, List<String> permissions, Boolean clientManaged) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, displayName, description, permissions, clientManaged);
        }
    }

    /// The four segments join into the canonical `application:context:aggregate:action` code.
    public record CreatePermissionRequest(String application, String context, String aggregate, String action, String description) {
    }

    public record RoleResponse(
            String id,
            String name,
            String shortName,
            String displayName,
            String description,
            List<String> permissions,
            String applicationCode,
            String source,
            boolean clientManaged,
            Instant createdAt,
            Instant updatedAt) {

        public static RoleResponse from(Role r) {
            return new RoleResponse(r.id(), r.name(), r.shortName(), r.displayName(), r.description(),
                    r.permissions(), r.applicationCode(), r.source().name(), r.clientManaged(), r.createdAt(), r.updatedAt());
        }
    }

    public record RoleListResponse(List<RoleResponse> items, int total) {
        public RoleListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static RoleListResponse from(List<Role> roles) {
            var out = roles.stream().map(RoleResponse::from).toList();
            return new RoleListResponse(out, out.size());
        }
    }

    public record ApplicationOption(String id, String code, String name) {
    }

    public record ApplicationOptionsResponse(List<ApplicationOption> options) {
        public ApplicationOptionsResponse {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /// `description` is a plain, always-present string (never omitted) — the
    /// SPA's `BffPermission.description` is not optional.
    public record PermissionResponse(String permission, String application, String context, String aggregate,
                                     String action, String description) {
        public static PermissionResponse from(Permission p) {
            return new PermissionResponse(p.code(), p.subdomain(), p.context(), p.aggregate(), p.action(),
                    p.description() == null ? "" : p.description());
        }
    }

    public record PermissionListResponse(List<PermissionResponse> items, int total) {
        public PermissionListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static PermissionListResponse from(List<Permission> rows) {
            var out = rows.stream().map(PermissionResponse::from).toList();
            return new PermissionListResponse(out, out.size());
        }
    }

    public record SyncPlatformResponse(int created, int updated, int removed, int total) {
    }
}

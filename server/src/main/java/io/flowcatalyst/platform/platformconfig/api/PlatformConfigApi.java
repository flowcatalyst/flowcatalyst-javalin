package io.flowcatalyst.platform.platformconfig.api;

import io.flowcatalyst.platform.platformconfig.ConfigAccess;
import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.platform.platformconfig.operations.Access;
import io.flowcatalyst.platform.platformconfig.operations.GrantAccess;
import io.flowcatalyst.platform.platformconfig.operations.GrantAccessCommand;
import io.flowcatalyst.platform.platformconfig.operations.RevokeAccess;
import io.flowcatalyst.platform.platformconfig.operations.RevokeAccessCommand;
import io.flowcatalyst.platform.platformconfig.operations.SetProperty;
import io.flowcatalyst.platform.platformconfig.operations.SetPropertyCommand;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
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

/// The platform-config surface (spec §4): the legacy `/api/platform-config/…`
/// list + grant routes and the SPA's `/api/config/{app}/{section}/{property}`
/// single-property routes. Gates are per application — anchor, or a role
/// with a grant ([Access]) — never permission codes; the grant routes are
/// anchor-only. A write handler does: gate → command from DTO →
/// `Operation.run` → response. Reads go straight to the repositories and
/// apply the secret-masking rule here. Every handler runs inside
/// [Auth#scoped] so the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/platform-config/{app}` | 200 [ConfigListResponse] |
/// | GET | `/api/config/{app}/{section}/{property}` | 200 [ConfigResponse] |
/// | PUT | `/api/config/{app}/{section}/{property}` | 200 [ConfigResponse] |
/// | DELETE | `/api/config/{app}/{section}/{property}` | 204 |
/// | GET | `/api/platform-config/{app}/access` | 200 [AccessListResponse] |
/// | POST | `/api/platform-config/{app}/access` | 201 [CreatedResponse] |
/// | DELETE | `/api/platform-config/access/{id}` | 204 |
public final class PlatformConfigApi {

    /// What a non-anchor sees in place of a `SECRET` value (spec §4).
    static final String MASKED_VALUE = "***";

    private PlatformConfigApi() {
    }

    /// The handlers' dependencies.
    public record State(PlatformConfigRepository configs, ConfigAccessRepository grants, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(configs, "configs");
            Objects.requireNonNull(grants, "grants");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/platform-config/{app}", Auth.scoped(ctx -> list(ctx, s)));
        routes.get("/api/config/{app}/{section}/{property}", Auth.scoped(ctx -> get(ctx, s)));
        routes.put("/api/config/{app}/{section}/{property}", Auth.scoped(ctx -> set(ctx, s)));
        routes.delete("/api/config/{app}/{section}/{property}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.get("/api/platform-config/{app}/access", Auth.scoped(ctx -> listAccess(ctx, s)));
        routes.post("/api/platform-config/{app}/access", Auth.scoped(ctx -> grant(ctx, s)));
        routes.delete("/api/platform-config/access/{id}", Auth.scoped(ctx -> revoke(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String app = ctx.pathParam("app");
        Access.requireRead(s.grants(), ac, app);
        ctx.json(new ConfigListResponse(s.configs().findByApplication(app).stream()
                .map(c -> ConfigResponse.from(visible(ac, c))).toList()));
    }

    private static void get(Context ctx, State s) {
        AuthContext ac = Auth.current();
        var coordinate = coordinate(ctx);
        Access.requireRead(s.grants(), ac, coordinate.applicationCode());
        ctx.json(ConfigResponse.from(visible(ac, configAt(s, coordinate))));
    }

    /// Answers with the value as re-read after the write, at the coordinate
    /// the command addressed (unmasked — spec §4, open question 5).
    private static void set(Context ctx, State s) {
        var cmd = ctx.bodyAsClass(SetPropertyRequest.class).toCommand(coordinate(ctx));
        SetProperty.of(s.configs(), s.grants()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(ConfigResponse.from(configAt(s, cmd.coordinate())));
    }

    /// Idempotent direct delete — no domain event, no audit row (spec §9,
    /// open question 3); committed through the unit of work so the write
    /// still goes through one transaction.
    private static void delete(Context ctx, State s) {
        var coordinate = coordinate(ctx);
        Access.requireWrite(s.grants(), Auth.current(), coordinate.applicationCode());
        s.configs().findByCoordinate(coordinate).ifPresent(c -> s.uow().inTransaction(tx -> {
            s.configs().delete(c, tx.dbTx());
            return null;
        }));
        ctx.status(204);
    }

    private static void listAccess(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        ctx.json(new AccessListResponse(s.grants().findByApplication(ctx.pathParam("app")).stream()
                .map(AccessResponse::from).toList()));
    }

    private static void grant(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(GrantAccessRequest.class).toCommand(ctx.pathParam("app"));
        var event = GrantAccess.of(s.grants()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.accessId()));
    }

    private static void revoke(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        RevokeAccess.of(s.grants()).run(s.uow(), new RevokeAccessCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// The coordinate a single-property route addresses: the three path
    /// segments plus the optional `clientId` query parameter (absent or
    /// empty ⇒ `GLOBAL`).
    private static ConfigCoordinate coordinate(Context ctx) {
        return ConfigCoordinate.of(ctx.pathParam("app"), ctx.pathParam("section"), ctx.pathParam("property"),
                blankToNull(ctx.queryParam("clientId")));
    }

    /// The value at `coordinate` or 404 `Config_NOT_FOUND` naming `app/section/property`.
    private static PlatformConfig configAt(State s, ConfigCoordinate coordinate) {
        return s.configs().findByCoordinate(coordinate)
                .orElseThrow(() -> HttpError.notFound("Config", coordinate.path()));
    }

    /// A `SECRET` value is masked for everyone but anchors.
    private static PlatformConfig visible(AuthContext ac, PlatformConfig c) {
        return c.isSecret() && !ac.isAnchor() ? c.withValue(MASKED_VALUE) : c;
    }

    /// The wire's "absent" for optional strings is `null` or `""`; inside the JVM it is `null`.
    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `PUT /api/config/{app}/{section}/{property}`. The path names
    /// the coordinate; a non-empty `clientId` **query** parameter (already
    /// folded into `coordinate`) overrides the body's `clientId`.
    public record SetPropertyRequest(String value, String valueType, String description, String clientId) {
        public SetPropertyCommand toCommand(ConfigCoordinate coordinate) {
            String client = coordinate.clientId() != null ? coordinate.clientId() : blankToNull(clientId);
            return new SetPropertyCommand(coordinate.applicationCode(), coordinate.section(), coordinate.property(),
                    value, valueType, description, client);
        }
    }

    /// Body of `POST /api/platform-config/{app}/access`; a missing `canWrite` reads as `false`.
    public record GrantAccessRequest(String roleCode, boolean canWrite) {
        public GrantAccessCommand toCommand(String applicationCode) {
            return new GrantAccessCommand(applicationCode, roleCode, canWrite);
        }
    }

    /// One config value on the wire; `clientId` and `description` are omitted when `null`.
    public record ConfigResponse(
            String id,
            String applicationCode,
            String section,
            String property,
            String scope,
            String clientId,
            String valueType,
            String value,
            String description,
            Instant createdAt,
            Instant updatedAt) {

        public static ConfigResponse from(PlatformConfig c) {
            return new ConfigResponse(c.id(), c.applicationCode(), c.section(), c.property(), c.scope().name(),
                    c.clientId(), c.valueType().name(), c.value(), c.description(), c.createdAt(), c.updatedAt());
        }
    }

    /// One access grant on the wire.
    public record AccessResponse(
            String id,
            String applicationCode,
            String roleCode,
            boolean canRead,
            boolean canWrite,
            Instant createdAt) {

        public static AccessResponse from(ConfigAccess a) {
            return new AccessResponse(a.id(), a.applicationCode(), a.roleCode(), a.canRead(), a.canWrite(), a.createdAt());
        }
    }

    /// `{"items": [...]}` — no pagination.
    public record ConfigListResponse(List<ConfigResponse> items) {
        public ConfigListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /// `{"items": [...]}` — no pagination.
    public record AccessListResponse(List<AccessResponse> items) {
        public AccessListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}

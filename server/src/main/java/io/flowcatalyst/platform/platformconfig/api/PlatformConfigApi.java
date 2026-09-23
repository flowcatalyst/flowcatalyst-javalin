package io.flowcatalyst.platform.platformconfig.api;

import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.platform.platformconfig.operations.SetProperty;
import io.flowcatalyst.platform.platformconfig.operations.SetPropertyCommand;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.CONFIG_MANAGE;
import static io.flowcatalyst.platform.shared.auth.Permission.CONFIG_VIEW;

/// The platform-config surface (`docs/spec/config-permissions.md` §A): the
/// legacy `/api/platform-config/{app}` list route and the SPA's
/// `/api/config/{app}/{section}/{property}` single-property routes. Gated by
/// permission codes like every other admin route — `CONFIG_VIEW` on the
/// reads, `CONFIG_MANAGE` on the writes — never by anchor scope or a
/// per-application grant table (the access-grant routes and their backing
/// table are withdrawn by this spec; `permissions-from-roles.md`: anchor is
/// reach, not authority). A write handler does: gate → command from DTO →
/// `Operation.run` → response. Reads go straight to the repository and
/// apply the secret-masking rule here. Every handler runs inside
/// [Auth#scoped] so the operations can read [Auth#current()].
///
/// | Method | Path | Status | Requires |
/// |---|---|---|---|
/// | GET | `/api/platform-config/{app}` | 200 [ConfigListResponse] | `CONFIG_VIEW` |
/// | GET | `/api/config/{app}/{section}/{property}` | 200 [ConfigResponse] | `CONFIG_VIEW` |
/// | PUT | `/api/config/{app}/{section}/{property}` | 200 [ConfigResponse] | `CONFIG_MANAGE` |
/// | DELETE | `/api/config/{app}/{section}/{property}` | 204 | `CONFIG_MANAGE` |
public final class PlatformConfigApi {

    private PlatformConfigApi() {
    }

    /// The handlers' dependencies.
    public record State(PlatformConfigRepository configs, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(configs, "configs");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/platform-config/{app}", Auth.scoped(ctx -> list(ctx, s)));
        routes.get("/api/config/{app}/{section}/{property}", Auth.scoped(ctx -> get(ctx, s)));
        write.put("/api/config/{app}/{section}/{property}", Auth.scoped(ctx -> set(ctx, s)));
        write.delete("/api/config/{app}/{section}/{property}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, CONFIG_VIEW);
        String app = ctx.pathParam("app");
        ctx.json(new ConfigListResponse(s.configs().findByApplication(app).stream()
                .map(c -> ConfigResponse.from(visible(ac, c))).toList()));
    }

    private static void get(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, CONFIG_VIEW);
        var coordinate = coordinate(ctx);
        ctx.json(ConfigResponse.from(visible(ac, configAt(s, coordinate))));
    }

    /// Gate lives in [SetProperty]'s authorize phase (spec's "Error handling":
    /// the operation's authorize phase is the established boundary for a
    /// command-scoped check). Answers with the value as re-read after the
    /// write, at the coordinate the command addressed (unmasked — spec §4,
    /// open question 5; the caller just proved `CONFIG_MANAGE`).
    private static void set(Exchange ctx, State s) {
        var cmd = ctx.bodyAsClass(SetPropertyRequest.class).toCommand(coordinate(ctx));
        SetProperty.of(s.configs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(ConfigResponse.from(configAt(s, cmd.coordinate())));
    }

    /// Idempotent direct delete — no domain event, no audit row (spec §9,
    /// open question 3); committed through the unit of work so the write
    /// still goes through one transaction.
    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), CONFIG_MANAGE);
        var coordinate = coordinate(ctx);
        s.configs().findByCoordinate(coordinate).ifPresent(c -> s.uow().inTransaction(tx -> {
            s.configs().delete(c, tx.dbTx());
            return null;
        }));
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// The coordinate a single-property route addresses: the three path
    /// segments plus the optional `clientId` query parameter (absent or
    /// empty ⇒ `GLOBAL`).
    private static ConfigCoordinate coordinate(Exchange ctx) {
        return ConfigCoordinate.of(ctx.pathParam("app"), ctx.pathParam("section"), ctx.pathParam("property"),
                blankToNull(ctx.queryParam("clientId")));
    }

    /// The value at `coordinate` or 404 `Config_NOT_FOUND` naming `app/section/property`.
    private static PlatformConfig configAt(State s, ConfigCoordinate coordinate) {
        return s.configs().findByCoordinate(coordinate)
                .orElseThrow(() -> HttpError.notFound("Config", coordinate.path()));
    }

    /// A `SECRET` value is masked for everyone but a `CONFIG_MANAGE` holder
    /// (spec §A.2) — `CONFIG_VIEW` alone sees the mask.
    private static PlatformConfig visible(AuthContext ac, PlatformConfig c) {
        return c.isSecret() && !ac.hasPermission(CONFIG_MANAGE) ? c.masked() : c;
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

    /// `{"items": [...]}` — no pagination.
    public record ConfigListResponse(List<ConfigResponse> items) {
        public ConfigListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}

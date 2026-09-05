package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.EventTypeRepository.ListFilter;
import io.flowcatalyst.platform.eventtype.SpecVersion;
import io.flowcatalyst.platform.eventtype.operations.AddSchema;
import io.flowcatalyst.platform.eventtype.operations.AddSchemaCommand;
import io.flowcatalyst.platform.eventtype.operations.ArchiveCommand;
import io.flowcatalyst.platform.eventtype.operations.ArchiveEventType;
import io.flowcatalyst.platform.eventtype.operations.CreateCommand;
import io.flowcatalyst.platform.eventtype.operations.CreateEventType;
import io.flowcatalyst.platform.eventtype.operations.DeleteCommand;
import io.flowcatalyst.platform.eventtype.operations.DeleteEventType;
import io.flowcatalyst.platform.eventtype.operations.DeprecateEventTypeSchema;
import io.flowcatalyst.platform.eventtype.operations.DeprecateSchemaCommand;
import io.flowcatalyst.platform.eventtype.operations.FinaliseEventTypeSchema;
import io.flowcatalyst.platform.eventtype.operations.FinaliseSchemaCommand;
import io.flowcatalyst.platform.eventtype.operations.SyncEventTypeInput;
import io.flowcatalyst.platform.eventtype.operations.SyncEventTypes;
import io.flowcatalyst.platform.eventtype.operations.SyncEventTypesCommand;
import io.flowcatalyst.platform.eventtype.operations.UpdateCommand;
import io.flowcatalyst.platform.eventtype.operations.UpdateEventType;
import io.flowcatalyst.platform.seed.PlatformEventTypes;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/bff/event-types` surface (bff spec §5) — the SPA's own shape for
/// event types, reusing [io.flowcatalyst.platform.eventtype.operations]
/// verbatim; only the wire DTOs differ from `/api/event-types`
/// ([io.flowcatalyst.platform.eventtype.api.EventTypeApi]). Gates mirror the
/// aggregate spec (`docs/spec/eventtype.md`), except `sync-platform` which
/// is anchor-only.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/bff/event-types` | 200 [EventTypeListResponse] |
/// | GET | `/bff/event-types/{id}` | 200 [EventTypeResponse] |
/// | POST | `/bff/event-types` | 201 [EventTypeResponse] |
/// | PUT | `/bff/event-types/{id}` | 204 |
/// | DELETE | `/bff/event-types/{id}` | 204 |
/// | POST | `/bff/event-types/{id}/archive` | 200 [EventTypeResponse] |
/// | POST | `/bff/event-types/{id}/schemas` | 200 [EventTypeResponse] |
/// | POST | `/bff/event-types/{id}/schemas/{version}/finalise` | 200 [EventTypeResponse] |
/// | POST | `/bff/event-types/{id}/schemas/{version}/deprecate` | 200 [EventTypeResponse] |
/// | POST | `/bff/event-types/sync-platform` | 200 [SyncPlatformResponse]; anchor-only |
///
/// **`PUT` vs `PATCH`:** the lockfile-owned aggregate route is `PUT`; the
/// SPA's own `event-types.ts` calls `PATCH` on this same path (§0: "the
/// frontend is the acceptance test"). Both methods are mounted onto the
/// same handler so the SPA works unchanged without dropping the documented `PUT`.
public final class EventTypesBff {

    private EventTypesBff() {
    }

    public record State(EventTypeRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/bff/event-types", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/bff/event-types", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/bff/event-types/filters/subdomains", Auth.scoped(ctx -> filterSubdomains(ctx, s)));
        routes.get("/bff/event-types/filters/aggregates", Auth.scoped(ctx -> filterAggregates(ctx, s)));
        routes.post("/bff/event-types/sync-platform", Auth.scoped(ctx -> syncPlatform(ctx, s)));
        routes.get("/bff/event-types/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        Handler update = Auth.scoped(ctx -> update(ctx, s));
        routes.put("/bff/event-types/{id}", update);
        routes.patch("/bff/event-types/{id}", update); // the SPA's own dialect (see class doc)
        routes.delete("/bff/event-types/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.post("/bff/event-types/{id}/archive", Auth.scoped(ctx -> archive(ctx, s)));
        routes.post("/bff/event-types/{id}/schemas", Auth.scoped(ctx -> addSchema(ctx, s)));
        routes.post("/bff/event-types/{id}/schemas/{version}/finalise", Auth.scoped(ctx -> finalise(ctx, s)));
        routes.post("/bff/event-types/{id}/schemas/{version}/deprecate", Auth.scoped(ctx -> deprecate(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, EVENT_TYPE_VIEW);
        List<EventType> visible = Checks.filterClientScoped(ac, s.repo().findWithFilters(listFilter(ctx)), EventType::clientId);
        ctx.json(new EventTypeListResponse(visible.stream().map(EventTypeResponse::from).toList()));
    }

    private static void getById(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, EVENT_TYPE_VIEW);
        ctx.json(EventTypeResponse.from(visible(ac, eventType(s, ctx.pathParam("id")))));
    }

    private static void create(Context ctx, State s) {
        Checks.requireAny(Auth.current(), EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
        var cmd = ctx.bodyAsClass(CreateEventTypeRequest.class).toCommand();
        var event = CreateEventType.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(EventTypeResponse.from(eventType(s, event.eventTypeId())));
    }

    private static void update(Context ctx, State s) {
        Checks.requireAny(Auth.current(), EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
        var cmd = ctx.bodyAsClass(UpdateEventTypeRequest.class).toCommand(ctx.pathParam("id"));
        UpdateEventType.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Context ctx, State s) {
        Checks.require(Auth.current(), EVENT_TYPE_DELETE);
        DeleteEventType.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void archive(Context ctx, State s) {
        Checks.requireAny(Auth.current(), EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
        String id = ctx.pathParam("id");
        ArchiveEventType.of(s.repo()).run(s.uow(), new ArchiveCommand(id), Auth.executionContext());
        ctx.json(EventTypeResponse.from(eventType(s, id)));
    }

    private static void addSchema(Context ctx, State s) {
        Checks.requireAny(Auth.current(), EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
        String id = ctx.pathParam("id");
        var cmd = ctx.bodyAsClass(AddSchemaRequest.class).toCommand(id);
        AddSchema.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(EventTypeResponse.from(eventType(s, id)));
    }

    private static void finalise(Context ctx, State s) {
        Checks.requireAny(Auth.current(), EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
        String id = ctx.pathParam("id");
        var cmd = new FinaliseSchemaCommand(id, ctx.pathParam("version"));
        FinaliseEventTypeSchema.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(EventTypeResponse.from(eventType(s, id)));
    }

    private static void deprecate(Context ctx, State s) {
        Checks.requireAny(Auth.current(), EVENT_TYPE_CREATE, EVENT_TYPE_UPDATE, EVENT_TYPE_DELETE);
        String id = ctx.pathParam("id");
        var cmd = new DeprecateSchemaCommand(id, ctx.pathParam("version"));
        DeprecateEventTypeSchema.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(EventTypeResponse.from(eventType(s, id)));
    }

    private static void filterSubdomains(Context ctx, State s) {
        String application = queryParam(ctx, "application");
        ctx.json(new OptionsResponse(application == null ? List.of() : s.repo().distinctSubdomains(application)));
    }

    private static void filterAggregates(Context ctx, State s) {
        String application = queryParam(ctx, "application");
        String subdomain = queryParam(ctx, "subdomain");
        ctx.json(new OptionsResponse(application == null || subdomain == null
                ? List.of() : s.repo().distinctAggregates(application, subdomain)));
    }

    /// Anchor-only: bulk-syncs the platform's built-in event-type catalogue
    /// (`seed.PlatformEventTypes`), defaulting `applicationCode` to
    /// `"platform"` when the body is absent/blank. The schema tally is
    /// wire-compatible but not instrumented on this path — `SyncEventTypes`
    /// applies name/description only, never a schema (Go parity, event_types.go).
    private static void syncPlatform(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        String applicationCode = "platform";
        if (!ctx.body().isBlank()) {
            var body = ctx.bodyAsClass(SyncPlatformRequest.class);
            if (body.applicationCode() != null && !body.applicationCode().isBlank()) applicationCode = body.applicationCode();
        }
        var defs = PlatformEventTypes.all();
        var inputs = defs.stream().map(d -> new SyncEventTypeInput(d.code(), d.name(), null, d.schema())).toList();
        var cmd = new SyncEventTypesCommand(applicationCode, inputs, true);
        var event = SyncEventTypes.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(new SyncPlatformResponse(event.created(), event.updated(), event.deleted(), defs.size(),
                new SyncPlatformSchemas(0, 0, 0)));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static ListFilter listFilter(Context ctx) {
        String application = queryParam(ctx, "application");
        String status = queryParam(ctx, "status");
        String subdomain = queryParam(ctx, "subdomain");
        String aggregate = queryParam(ctx, "aggregate");
        return new ListFilter(application, status, subdomain, aggregate);
    }

    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static EventType eventType(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("EventType", id));
    }

    /// A client-scoped event type is visible only to principals with access to that client.
    private static EventType visible(AuthContext ac, EventType et) {
        if (et.clientId() != null && !ac.canAccessClient(et.clientId())) {
            throw HttpError.forbidden("No access to this event type");
        }
        return et;
    }

    // ── Wire DTOs (SPA shape, bff spec §5) ──────────────────────────────────

    public record CreateEventTypeRequest(String code, String name, String description, JsonNode schema, String clientId) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, description, clientId, schema);
        }
    }

    public record UpdateEventTypeRequest(String name, String description) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description);
        }
    }

    public record AddSchemaRequest(JsonNode schema, String mimeType, String schemaType, String version) {
        public AddSchemaCommand toCommand(String id) {
            return new AddSchemaCommand(id, version, schema);
        }
    }

    /// Optional body of `POST /bff/event-types/sync-platform`.
    public record SyncPlatformRequest(String applicationCode) {
    }

    public record EventTypeResponse(
            String id,
            String code,
            String application,
            String subdomain,
            String aggregate,
            String event,
            String name,
            String description,
            String status,
            boolean clientScoped,
            List<SpecVersionResponse> specVersions,
            Instant createdAt,
            Instant updatedAt) {

        public static EventTypeResponse from(EventType et) {
            return new EventTypeResponse(et.id(), et.code(), et.application(), et.subdomain(), et.aggregate(),
                    et.eventName(), et.name(), et.description(), et.status().name(), et.clientScoped(),
                    et.specVersions().stream().map(SpecVersionResponse::from).toList(), et.createdAt(), et.updatedAt());
        }
    }

    /// `schema` is the stringified JSON content — the SPA's editor re-parses it lazily.
    public record SpecVersionResponse(
            String id,
            String version,
            String status,
            String schemaType,
            String mimeType,
            String schema,
            Instant createdAt,
            Instant updatedAt) {

        public static SpecVersionResponse from(SpecVersion sv) {
            return new SpecVersionResponse(sv.id(), sv.version(), sv.status().name(), sv.schemaType().name(),
                    sv.mimeType(), sv.schemaContent() == null ? null : Json.write(sv.schemaContent()),
                    sv.createdAt(), sv.updatedAt());
        }
    }

    public record EventTypeListResponse(List<EventTypeResponse> items, int total) {
        public EventTypeListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public EventTypeListResponse(List<EventTypeResponse> items) {
            this(items, items == null ? 0 : items.size());
        }
    }

    public record OptionsResponse(List<String> options) {
        public OptionsResponse {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public record SyncPlatformSchemas(int created, int updated, int unchanged) {
    }

    public record SyncPlatformResponse(int created, int updated, int deleted, int total, SyncPlatformSchemas schemas) {
    }
}

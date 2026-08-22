package io.flowcatalyst.platform.eventtype.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.EventTypeRepository.ListFilter;
import io.flowcatalyst.platform.eventtype.SpecVersion;
import io.flowcatalyst.platform.eventtype.operations.AddSchema;
import io.flowcatalyst.platform.eventtype.operations.AddSchemaCommand;
import io.flowcatalyst.platform.eventtype.operations.CreateCommand;
import io.flowcatalyst.platform.eventtype.operations.CreateEventType;
import io.flowcatalyst.platform.eventtype.operations.DeleteCommand;
import io.flowcatalyst.platform.eventtype.operations.DeleteEventType;
import io.flowcatalyst.platform.eventtype.operations.UpdateCommand;
import io.flowcatalyst.platform.eventtype.operations.UpdateEventType;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The `/api/event-types` surface (spec §3). A write handler does exactly:
/// coarse permission → command from DTO → `Operation.run` → response. Reads
/// go straight to the repository and apply the client-scope visibility rule
/// here. Every handler runs inside [Auth#scoped] so the operations can read
/// [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/event-types` | 200 [EventTypeListResponse] |
/// | POST | `/api/event-types` | 201 [CreatedResponse] |
/// | GET | `/api/event-types/{id}` | 200 [EventTypeResponse] |
/// | GET | `/api/event-types/by-code/{code}` | 200 [EventTypeResponse] |
/// | PUT | `/api/event-types/{id}` | 204 |
/// | DELETE | `/api/event-types/{id}` | 204 |
/// | POST | `/api/event-types/{id}/schemas` | 200 [EventTypeResponse] (alias) |
/// | POST | `/api/event-types/{id}/versions` | 200 [EventTypeResponse] |
public final class EventTypeApi {

    private EventTypeApi() {
    }

    /// The handlers' dependencies.
    public record State(EventTypeRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/event-types", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/event-types", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/event-types/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.get("/api/event-types/by-code/{code}", Auth.scoped(ctx -> getByCode(ctx, s)));
        routes.put("/api/event-types/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.delete("/api/event-types/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        Handler addSchema = Auth.scoped(ctx -> addSchema(ctx, s));
        routes.post("/api/event-types/{id}/versions", addSchema);
        routes.post("/api/event-types/{id}/schemas", addSchema); // historical alias, SPA clients still use it
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.canReadEventTypes(ac);
        List<EventType> visible = Checks.filterClientScoped(ac, s.repo().findWithFilters(listFilter(ctx)), EventType::clientId);
        ctx.json(new EventTypeListResponse(visible.stream().map(EventTypeResponse::from).toList()));
    }

    private static void getById(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.canReadEventTypes(ac);
        String id = ctx.pathParam("id");
        ctx.json(EventTypeResponse.from(visible(ac, s.repo().findById(id).orElseThrow(() -> HttpError.notFound("EventType", id)))));
    }

    private static void getByCode(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.canReadEventTypes(ac);
        String code = ctx.pathParam("code");
        ctx.json(EventTypeResponse.from(visible(ac, s.repo().findByCode(code).orElseThrow(() -> HttpError.notFound("EventType", code)))));
    }

    private static void create(Context ctx, State s) {
        Checks.canWriteEventTypes(Auth.current());
        var cmd = ctx.bodyAsClass(CreateEventTypeRequest.class).toCommand();
        var event = CreateEventType.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.eventTypeId()));
    }

    private static void update(Context ctx, State s) {
        Checks.canWriteEventTypes(Auth.current());
        var cmd = ctx.bodyAsClass(UpdateEventTypeRequest.class).toCommand(ctx.pathParam("id"));
        UpdateEventType.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Context ctx, State s) {
        Checks.canDeleteEventTypes(Auth.current());
        DeleteEventType.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    /// Answers with the updated event type (re-read after the write), as the lockfile says.
    private static void addSchema(Context ctx, State s) {
        Checks.canWriteEventTypes(Auth.current());
        String id = ctx.pathParam("id");
        AddSchema.of(s.repo()).run(s.uow(), ctx.bodyAsClass(AddSchemaRequest.class).toCommand(id), Auth.executionContext());
        ctx.json(EventTypeResponse.from(s.repo().findById(id).orElseThrow(() -> HttpError.notFound("EventType", id))));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter. With no query parameter at all, `status=CURRENT`
    /// is implied. `clientId` is accepted for wire parity but is not a column:
    /// it filters nothing, yet its presence counts as "filtered" for the
    /// default (spec §3, open question 3).
    private static ListFilter listFilter(Context ctx) {
        String application = queryParam(ctx, "application");
        String clientId = queryParam(ctx, "clientId");
        String status = queryParam(ctx, "status");
        String subdomain = queryParam(ctx, "subdomain");
        String aggregate = queryParam(ctx, "aggregate");
        boolean unfiltered = application == null && clientId == null && status == null && subdomain == null && aggregate == null;
        return new ListFilter(application, unfiltered ? "CURRENT" : status, subdomain, aggregate);
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /// A client-scoped event type is visible only to principals with access to that client.
    private static EventType visible(AuthContext ac, EventType et) {
        if (et.clientId() != null && !ac.canAccessClient(et.clientId())) {
            throw HttpError.forbidden("No access to this event type");
        }
        return et;
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/event-types`.
    public record CreateEventTypeRequest(String code, String name, String description, String clientId, JsonNode schema) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, description, clientId, schema);
        }
    }

    /// Body of `PUT /api/event-types/{id}`; the path id is authoritative, a body `id` is ignored.
    public record UpdateEventTypeRequest(String name, String description) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description);
        }
    }

    /// Body of `POST /api/event-types/{id}/schemas|versions`.
    public record AddSchemaRequest(String version, JsonNode schema) {
        public AddSchemaCommand toCommand(String id) {
            return new AddSchemaCommand(id, version, schema);
        }
    }

    /// The wire shape of one event type; optional fields (`description`,
    /// `clientId`, `createdBy`) are omitted when `null`.
    public record EventTypeResponse(
            String id,
            String code,
            String name,
            String application,
            String subdomain,
            String aggregate,
            String eventName,
            String description,
            String status,
            String source,
            String clientId,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<SpecVersionResponse> specVersions) {

        public static EventTypeResponse from(EventType et) {
            return new EventTypeResponse(et.id(), et.code(), et.name(), et.application(), et.subdomain(),
                    et.aggregate(), et.eventName(), et.description(), et.status().name(), et.source().name(),
                    et.clientId(), et.createdBy(), et.createdAt(), et.updatedAt(),
                    et.specVersions().stream().map(SpecVersionResponse::from).toList());
        }
    }

    /// One spec version on the wire: `schema` is always present (`null` when
    /// the version has no stored content) — the lockfile marks it required.
    public record SpecVersionResponse(
            String version,
            @JsonInclude(JsonInclude.Include.ALWAYS) JsonNode schema,
            String status,
            Instant createdAt) {

        public static SpecVersionResponse from(SpecVersion sv) {
            return new SpecVersionResponse(sv.version(), sv.schemaContent(), sv.status().name(), sv.createdAt());
        }
    }

    /// `{"items": [...]}` — no pagination on this endpoint.
    public record EventTypeListResponse(List<EventTypeResponse> items) {
        public EventTypeListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}

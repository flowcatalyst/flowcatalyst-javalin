package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.SpecVersion;
import io.flowcatalyst.platform.openapispecs.ChangeNotes;
import io.flowcatalyst.platform.openapispecs.OpenApiSpec;
import io.flowcatalyst.platform.openapispecs.OpenApiSpecRepository;
import io.flowcatalyst.platform.openapispecs.operations.SyncOpenApiSpec;
import io.flowcatalyst.platform.openapispecs.operations.SyncOpenApiSpecCommand;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static io.flowcatalyst.platform.shared.auth.Permission.APPLICATION_OPENAPI_SYNC;
import static io.flowcatalyst.platform.shared.auth.Permission.APPLICATION_OPENAPI_VIEW;

/// The `/bff/developer/*` surface (bff spec §4): read-only application /
/// OpenAPI-spec / event-type lookups for the developer portal, plus the one
/// write endpoint that syncs the platform's own generated OpenAPI document.
/// Every route is anchor-only, followed by the permission gate
/// (`docs/spec/reach-only-routes.md`).
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/bff/developer/applications` | 200 [ApplicationsResponse] |
/// | GET | `/bff/developer/applications/{appId}` | 200 [ApplicationSummary]; 404 |
/// | GET | `/bff/developer/applications/{appId}/openapi/current` | 200 [SpecResponse]; 404 |
/// | GET | `/bff/developer/applications/{appId}/openapi/versions` | 200 [VersionsResponse] |
/// | GET | `/bff/developer/applications/{appId}/openapi/versions/{specId}` | 200 [SpecResponse]; 404 |
/// | GET | `/bff/developer/applications/{appId}/event-types` | 200 [EventTypesResponse] |
/// | POST | `/bff/developer/sync-platform-openapi` | 200 [SyncPlatformOpenApiResponse] |
public final class DeveloperBff {

    /// The application row the platform's own OpenAPI document syncs against
    /// (`docs/spec/bff.md` §4, Go's seeded `"platform"` application).
    static final String PLATFORM_APPLICATION_CODE = "platform";

    private DeveloperBff() {
    }

    /// `platformOpenApi` is a lazy accessor (not a cached byte array), the
    /// way Go's `DeveloperState.PlatformOpenAPI` is: the live document, not a
    /// boot-time snapshot.
    public record State(ApplicationRepository applications, OpenApiSpecRepository specs, EventTypeRepository eventTypes,
                        UnitOfWork uow, Supplier<JsonNode> platformOpenApi) {
        public State {
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(specs, "specs");
            Objects.requireNonNull(eventTypes, "eventTypes");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(platformOpenApi, "platformOpenApi");
        }
    }

    public static void register(Routes routes, State s) {
        routes.get("/bff/developer/applications", Auth.scoped(ctx -> listApplications(ctx, s)));
        routes.post("/bff/developer/sync-platform-openapi", Auth.scoped(ctx -> syncPlatformOpenApi(ctx, s)));
        routes.get("/bff/developer/applications/{appId}", Auth.scoped(ctx -> getApplication(ctx, s)));
        routes.get("/bff/developer/applications/{appId}/openapi/current", Auth.scoped(ctx -> getCurrentSpec(ctx, s)));
        routes.get("/bff/developer/applications/{appId}/openapi/versions", Auth.scoped(ctx -> listVersions(ctx, s)));
        routes.get("/bff/developer/applications/{appId}/openapi/versions/{specId}", Auth.scoped(ctx -> getVersion(ctx, s)));
        routes.get("/bff/developer/applications/{appId}/event-types", Auth.scoped(ctx -> listEventTypes(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void listApplications(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), APPLICATION_OPENAPI_VIEW);
        var apps = s.applications().findWithFilters(new ApplicationRepository.ListFilter(null, true));
        var out = apps.stream().map(a -> toSummary(a, s.specs().findCurrentByApplication(a.id()).orElse(null))).toList();
        ctx.json(new ApplicationsResponse(out));
    }

    private static void getApplication(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), APPLICATION_OPENAPI_VIEW);
        Application app = application(s, ctx.pathParam("appId"));
        ctx.json(toSummary(app, s.specs().findCurrentByApplication(app.id()).orElse(null)));
    }

    private static void getCurrentSpec(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), APPLICATION_OPENAPI_VIEW);
        String appId = ctx.pathParam("appId");
        OpenApiSpec spec = s.specs().findCurrentByApplication(appId).orElseThrow(() -> HttpError.notFound("OpenApiSpec", appId));
        ctx.json(SpecResponse.from(spec));
    }

    private static void listVersions(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), APPLICATION_OPENAPI_VIEW);
        var specs = s.specs().findAllByApplication(ctx.pathParam("appId"));
        ctx.json(new VersionsResponse(specs.stream().map(VersionSummary::from).toList()));
    }

    private static void getVersion(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), APPLICATION_OPENAPI_VIEW);
        String appId = ctx.pathParam("appId");
        String specId = ctx.pathParam("specId");
        OpenApiSpec spec = s.specs().findById(specId).filter(sp -> sp.applicationId().equals(appId))
                .orElseThrow(() -> HttpError.notFound("OpenApiSpec", specId));
        ctx.json(SpecResponse.from(spec));
    }

    private static void listEventTypes(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), APPLICATION_OPENAPI_VIEW);
        Application app = application(s, ctx.pathParam("appId"));
        var out = s.eventTypes().findByApplication(app.code()).stream().map(EventTypeSummary::from).toList();
        ctx.json(new EventTypesResponse(out));
    }

    /// Captures the live generated platform OpenAPI document and runs the
    /// sync use case against the seeded `platform` application row.
    private static void syncPlatformOpenApi(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), APPLICATION_OPENAPI_SYNC);
        Application app = s.applications().findByCode(PLATFORM_APPLICATION_CODE)
                .orElseThrow(() -> UseCaseException.internal("SEED", "platform application missing - run seed", null));
        var cmd = new SyncOpenApiSpecCommand(app.id(), app.code(), s.platformOpenApi().get());
        var event = SyncOpenApiSpec.of(s.specs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(new SyncPlatformOpenApiResponse(event.applicationCode(), event.specId(), event.version(),
                event.unchanged() ? "UNCHANGED" : "CURRENT", event.archivedPriorVersion(), event.hasBreaking(), event.unchanged()));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    private static Application application(State s, String id) {
        return s.applications().findById(id).orElseThrow(() -> HttpError.notFound("Application", id));
    }

    private static ApplicationSummary toSummary(Application a, OpenApiSpec current) {
        return new ApplicationSummary(a.id(), a.code(), a.name(), a.description(), a.iconUrl(),
                current == null ? null : current.version(), current == null ? null : current.id(),
                current == null ? null : current.syncedAt());
    }

    // ── Wire DTOs (SPA shape, bff spec §4) ──────────────────────────────────

    public record ApplicationSummary(String id, String code, String name, String description, String iconUrl,
                                     String currentVersion, String currentSpecId, Instant currentSyncedAt) {
    }

    public record ApplicationsResponse(List<ApplicationSummary> items) {
        public ApplicationsResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record SpecResponse(String id, String applicationId, String version, String status, JsonNode spec,
                               String changeNotesText, ChangeNotes changeNotes, Instant syncedAt) {
        public static SpecResponse from(OpenApiSpec s) {
            return new SpecResponse(s.id(), s.applicationId(), s.version(), s.status().name(), s.spec(),
                    s.changeNotesText(), s.changeNotes(), s.syncedAt());
        }
    }

    public record VersionSummary(String id, String version, String status, String changeNotesText, boolean hasBreaking,
                                 Instant syncedAt) {
        public static VersionSummary from(OpenApiSpec s) {
            return new VersionSummary(s.id(), s.version(), s.status().name(), s.changeNotesText(),
                    s.changeNotes() != null && s.changeNotes().hasBreaking(), s.syncedAt());
        }
    }

    public record VersionsResponse(List<VersionSummary> items) {
        public VersionsResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record EventTypeSpecVersionSummary(String id, String version, String status, String schema) {
        static EventTypeSpecVersionSummary from(SpecVersion sv) {
            return new EventTypeSpecVersionSummary(sv.id(), sv.version(), sv.status().name(),
                    sv.schemaContent() == null ? null : Json.write(sv.schemaContent()));
        }
    }

    public record EventTypeSummary(String id, String code, String name, String description, String status,
                                   String application, String subdomain, String aggregate, String eventName,
                                   List<EventTypeSpecVersionSummary> specVersions) {
        static EventTypeSummary from(EventType et) {
            return new EventTypeSummary(et.id(), et.code(), et.name(), et.description(), et.status().name(),
                    et.application(), et.subdomain(), et.aggregate(), et.eventName(),
                    et.specVersions().stream().map(EventTypeSpecVersionSummary::from).toList());
        }
    }

    public record EventTypesResponse(List<EventTypeSummary> items) {
        public EventTypesResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record SyncPlatformOpenApiResponse(String applicationCode, String specId, String version, String status,
                                              String archivedPriorVersion, boolean hasBreaking, boolean unchanged) {
    }
}

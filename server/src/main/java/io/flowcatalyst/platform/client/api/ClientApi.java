package io.flowcatalyst.platform.client.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationRepository.ListFilter;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.operations.DisableApplicationForClient;
import io.flowcatalyst.platform.application.operations.DisableForClientCommand;
import io.flowcatalyst.platform.application.operations.EnableApplicationForClient;
import io.flowcatalyst.platform.application.operations.EnableForClientCommand;
import io.flowcatalyst.platform.application.operations.UpdateClientApplications;
import io.flowcatalyst.platform.application.operations.UpdateClientApplicationsCommand;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientNote;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ActivateClient;
import io.flowcatalyst.platform.client.operations.ActivateCommand;
import io.flowcatalyst.platform.client.operations.AddNote;
import io.flowcatalyst.platform.client.operations.AddNoteCommand;
import io.flowcatalyst.platform.client.operations.CreateClient;
import io.flowcatalyst.platform.client.operations.CreateCommand;
import io.flowcatalyst.platform.client.operations.DeleteClient;
import io.flowcatalyst.platform.client.operations.DeleteCommand;
import io.flowcatalyst.platform.client.operations.SuspendClient;
import io.flowcatalyst.platform.client.operations.SuspendCommand;
import io.flowcatalyst.platform.client.operations.UpdateClient;
import io.flowcatalyst.platform.client.operations.UpdateCommand;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.apicommon.StatusChangeResponse;
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
import java.util.Map;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_ACTIVATE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_DEACTIVATE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_SUSPEND;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.CLIENT_VIEW;
import static java.util.stream.Collectors.toMap;

/// The `/api/clients` surface (spec §3). Clients are anchor-only: every
/// handler opens with `requireAnchor` (the one exception, the applications
/// read, admits a principal with access to that client), followed by the
/// permission gate (`docs/spec/reach-only-routes.md`). A write handler
/// does exactly: gate → command from DTO → `Operation.run` → response.
/// Reads go straight to the repository. Every handler runs inside
/// [Auth#scoped] so the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/clients` | 200 [ClientListResponse] |
/// | POST | `/api/clients` | 201 [CreatedResponse] |
/// | POST | `/api/clients/search` | 200 [ClientListResponse] |
/// | GET | `/api/clients/search?q=` | 200 [ClientListResponse] (SDK alias) |
/// | GET | `/api/clients/by-identifier/{identifier}` | 200 [ClientResponse] |
/// | GET | `/api/clients/{id}` | 200 [ClientResponse] |
/// | PUT | `/api/clients/{id}` | 204 |
/// | DELETE | `/api/clients/{id}` | 204 |
/// | POST | `/api/clients/{id}/activate` | 200 [StatusChangeResponse] |
/// | POST | `/api/clients/{id}/suspend` | 200 [StatusChangeResponse] |
/// | POST | `/api/clients/{id}/notes` | 200 [StatusChangeResponse] |
/// | POST | `/api/clients/{id}/deactivate` | 200 [StatusChangeResponse] (delete alias) |
/// | GET | `/api/clients/{id}/applications` | 200 [ClientApplicationsResponse] |
/// | PUT | `/api/clients/{id}/applications` | 204 |
/// | POST | `/api/clients/{id}/applications/{applicationId}/enable` | 204 |
/// | POST | `/api/clients/{id}/applications/{applicationId}/disable` | 204 |
///
/// The four `/{id}/applications*` routes are the client-side face of the
/// application aggregate's client configs: the handlers here hold the gate,
/// the operations and events are `platform.application`'s (spec §10).
public final class ClientApi {

    private ClientApi() {
    }

    /// The handlers' dependencies; `applications` / `clientConfigs` serve the
    /// client → application routes (spec §10).
    public record State(ClientRepository repo, ApplicationRepository applications, ClientConfigRepository clientConfigs,
                        UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(clientConfigs, "clientConfigs");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the
    /// lockfile's. Literal segments are registered before the `{id}` routes
    /// so they take precedence (spec §3).
    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/clients", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/clients", Auth.scoped(ctx -> create(ctx, s)));
        routes.post("/api/clients/search", Auth.scoped(ctx -> search(ctx, s)));
        routes.get("/api/clients/search", Auth.scoped(ctx -> searchByQuery(ctx, s))); // SDK alias
        routes.get("/api/clients/by-identifier/{identifier}", Auth.scoped(ctx -> getByIdentifier(ctx, s)));
        routes.get("/api/clients/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        write.put("/api/clients/{id}", Auth.scoped(ctx -> update(ctx, s)));
        write.delete("/api/clients/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        write.post("/api/clients/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
        write.post("/api/clients/{id}/suspend", Auth.scoped(ctx -> suspend(ctx, s)));
        write.post("/api/clients/{id}/notes", Auth.scoped(ctx -> addNote(ctx, s)));
        write.post("/api/clients/{id}/deactivate", Auth.scoped(ctx -> deactivate(ctx, s))); // delete alias
        routes.get("/api/clients/{id}/applications", Auth.scoped(ctx -> applications(ctx, s)));
        write.put("/api/clients/{id}/applications", Auth.scoped(ctx -> updateApplications(ctx, s)));
        write.post("/api/clients/{id}/applications/{applicationId}/enable", Auth.scoped(ctx -> enableApplication(ctx, s)));
        write.post("/api/clients/{id}/applications/{applicationId}/disable", Auth.scoped(ctx -> disableApplication(ctx, s)));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_VIEW);
        ctx.json(ClientListResponse.from(s.repo().findAll()));
    }

    private static void search(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_VIEW);
        ctx.json(ClientListResponse.from(s.repo().search(ctx.bodyAsClass(SearchClientRequest.class).term())));
    }

    /// `?q=` absent → no term → the first 50 clients.
    private static void searchByQuery(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_VIEW);
        ctx.json(ClientListResponse.from(s.repo().search(ctx.queryParam("q"))));
    }

    private static void getByIdentifier(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_VIEW);
        String identifier = ctx.pathParam("identifier");
        ctx.json(ClientResponse.from(s.repo().findByIdentifier(identifier).orElseThrow(() -> HttpError.notFound("Client", identifier))));
    }

    private static void getById(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_VIEW);
        ctx.json(ClientResponse.from(load(s, ctx.pathParam("id"))));
    }

    /// The only non-anchor route: a principal with access to this client may
    /// see which applications are enabled for it (spec §3).
    private static void applications(Exchange ctx, State s) {
        String id = ctx.pathParam("id");
        requireAnchorOrClientAccess(Auth.current(), id);
        Checks.require(Auth.current(), CLIENT_VIEW);
        load(s, id);
        ctx.json(ClientApplicationsResponse.from(s.applications().findWithFilters(new ListFilter(null, null)), enabledByApplication(s, id)));
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    private static void create(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_CREATE);
        var cmd = ctx.bodyAsClass(CreateClientRequest.class).toCommand();
        var event = CreateClient.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.clientId()));
    }

    private static void update(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_UPDATE);
        var cmd = ctx.bodyAsClass(UpdateClientRequest.class).toCommand(ctx.pathParam("id"));
        UpdateClient.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_DELETE);
        DeleteClient.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void activate(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_ACTIVATE);
        ActivateClient.of(s.repo()).run(s.uow(), new ActivateCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Client activated"));
    }

    private static void suspend(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_SUSPEND);
        var cmd = ctx.bodyAsClass(SuspendClientRequest.class).toCommand(ctx.pathParam("id"));
        SuspendClient.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(new StatusChangeResponse("Client suspended"));
    }

    private static void addNote(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_UPDATE);
        var cmd = ctx.bodyAsClass(AddNoteRequest.class).toCommand(ctx.pathParam("id"));
        AddNote.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(new StatusChangeResponse("Note added"));
    }

    /// Alias of delete: a hard delete; the body's `reason` is read for shape
    /// and discarded (spec §3, open question 2).
    private static void deactivate(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_DEACTIVATE);
        ctx.bodyAsClass(StatusChangeRequest.class);
        DeleteClient.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Client deactivated"));
    }

    // ── Client → application linking (application aggregate's operations) ──

    private static void updateApplications(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_UPDATE);
        var cmd = ctx.bodyAsClass(UpdateClientApplicationsRequest.class).toCommand(ctx.pathParam("id"));
        UpdateClientApplications.of(s.applications(), s.clientConfigs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void enableApplication(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_UPDATE);
        var cmd = new EnableForClientCommand(ctx.pathParam("applicationId"), ctx.pathParam("id"));
        EnableApplicationForClient.of(s.applications(), s.clientConfigs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void disableApplication(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), CLIENT_UPDATE);
        var cmd = new DisableForClientCommand(ctx.pathParam("applicationId"), ctx.pathParam("id"));
        DisableApplicationForClient.of(s.clientConfigs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// The client's `enabled` flag per application id (a later config row wins if several exist).
    private static Map<String, Boolean> enabledByApplication(State s, String clientId) {
        return s.clientConfigs().findByClient(clientId).stream()
                .collect(toMap(ClientConfig::applicationId, ClientConfig::enabled, (_, last) -> last));
    }

    private static Client load(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("Client", id));
    }

    /// Anchor, or a principal whose scope includes `clientId`; otherwise 403 `FORBIDDEN`.
    private static void requireAnchorOrClientAccess(AuthContext ac, String clientId) {
        if (ac == null) throw HttpError.unauthenticated();
        if (ac.isAnchor() || ac.canAccessClient(clientId)) return;
        throw HttpError.forbidden("No access to this client");
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/clients`.
    public record CreateClientRequest(String name, String identifier) {
        public CreateCommand toCommand() {
            return new CreateCommand(name, identifier);
        }
    }

    /// Body of `PUT /api/clients/{id}`; `name` absent = unchanged.
    public record UpdateClientRequest(String name) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name);
        }
    }

    /// Body of `POST /api/clients/{id}/suspend`.
    public record SuspendClientRequest(String reason) {
        public SuspendCommand toCommand(String id) {
            return new SuspendCommand(id, reason);
        }
    }

    /// Body of `POST /api/clients/{id}/notes`.
    public record AddNoteRequest(String category, String text) {
        public AddNoteCommand toCommand(String id) {
            return new AddNoteCommand(id, category, text);
        }
    }

    /// Body of `POST /api/clients/search`; an absent term searches everything.
    public record SearchClientRequest(String term) {
    }

    /// Body of `POST /api/clients/{id}/deactivate`. The reason is not used (spec §3).
    public record StatusChangeRequest(String reason) {
    }

    /// Body of `PUT /api/clients/{id}/applications`: the desired enabled set.
    public record UpdateClientApplicationsRequest(List<String> enabledApplicationIds) {
        public UpdateClientApplicationsCommand toCommand(String clientId) {
            return new UpdateClientApplicationsCommand(clientId, enabledApplicationIds);
        }
    }

    /// The wire shape of one client; `statusReason` and `statusChangedAt`
    /// are omitted when `null`; `notes` is always present.
    public record ClientResponse(
            String id,
            String name,
            String identifier,
            String status,
            String statusReason,
            Instant statusChangedAt,
            List<NoteResponse> notes,
            Instant createdAt,
            Instant updatedAt) {

        public static ClientResponse from(Client c) {
            return new ClientResponse(c.id(), c.name(), c.identifier(), c.status().name(), c.statusReason(),
                    c.statusChangedAt(), c.notes().stream().map(NoteResponse::from).toList(), c.createdAt(), c.updatedAt());
        }
    }

    /// One note on the wire; `addedBy` omitted when `null`.
    public record NoteResponse(String category, String text, String addedBy, Instant addedAt) {
        public static NoteResponse from(ClientNote n) {
            return new NoteResponse(n.category(), n.text(), n.addedBy(), n.addedAt());
        }
    }

    /// `{"clients": [...], "total": n}` — `total` is the list size (no pagination).
    public record ClientListResponse(List<ClientResponse> clients, int total) {
        public ClientListResponse {
            clients = clients == null ? List.of() : List.copyOf(clients);
        }

        public static ClientListResponse from(List<Client> clients) {
            var items = clients.stream().map(ClientResponse::from).toList();
            return new ClientListResponse(items, items.size());
        }
    }

    /// One application as seen from a client; `description` / `iconUrl` omitted when `null`;
    /// `enabledForClient` is `false` when the client has no config row for it.
    public record ClientApplicationResponse(String id, String code, String name, String description, String iconUrl,
                                            boolean active, boolean enabledForClient) {
        public static ClientApplicationResponse from(Application a, boolean enabledForClient) {
            return new ClientApplicationResponse(a.id(), a.code(), a.name(), a.description(), a.iconUrl(), a.active(),
                    enabledForClient);
        }
    }

    /// `{"applications": [...], "total": n}` — every application, in code order.
    public record ClientApplicationsResponse(List<ClientApplicationResponse> applications, int total) {
        public ClientApplicationsResponse {
            applications = applications == null ? List.of() : List.copyOf(applications);
        }

        public static ClientApplicationsResponse from(List<Application> apps, Map<String, Boolean> enabledByApplication) {
            var items = apps.stream()
                    .map(a -> ClientApplicationResponse.from(a, enabledByApplication.getOrDefault(a.id(), false)))
                    .toList();
            return new ClientApplicationsResponse(items, items.size());
        }
    }
}

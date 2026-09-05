package io.flowcatalyst.platform.application.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationRepository.ListFilter;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.operations.ActivateApplication;
import io.flowcatalyst.platform.application.operations.ActivateCommand;
import io.flowcatalyst.platform.application.operations.AttachServiceAccount;
import io.flowcatalyst.platform.application.operations.AttachServiceAccountCommand;
import io.flowcatalyst.platform.application.operations.CreateApplication;
import io.flowcatalyst.platform.application.operations.CreateCommand;
import io.flowcatalyst.platform.application.operations.DeactivateApplication;
import io.flowcatalyst.platform.application.operations.DeactivateCommand;
import io.flowcatalyst.platform.application.operations.DeleteApplication;
import io.flowcatalyst.platform.application.operations.DeleteCommand;
import io.flowcatalyst.platform.application.operations.DisableApplicationForClient;
import io.flowcatalyst.platform.application.operations.DisableForClientCommand;
import io.flowcatalyst.platform.application.operations.EnableApplicationForClient;
import io.flowcatalyst.platform.application.operations.EnableForClientCommand;
import io.flowcatalyst.platform.application.operations.ProvisionServiceAccount;
import io.flowcatalyst.platform.application.operations.ProvisionServiceAccountCommand;
import io.flowcatalyst.platform.application.operations.UpdateApplication;
import io.flowcatalyst.platform.application.operations.UpdateCommand;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.CreateOAuthClient;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientCreated;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/api/applications` surface (spec §3). A write handler does exactly:
/// coarse permission → command from DTO → `Operation.run` → response. Reads
/// go straight to the repositories. Every handler runs inside [Auth#scoped]
/// so the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/applications` | 200 [ApplicationListResponse] |
/// | POST | `/api/applications` | 201 [CreatedResponse] |
/// | GET | `/api/applications/by-code/{code}` | 200 [ApplicationResponse] |
/// | GET | `/api/applications/by-id/{id}/roles` | 200 [ApplicationRolesResponse] |
/// | GET | `/api/applications/{id}` | 200 [ApplicationResponse] |
/// | PUT | `/api/applications/{id}` | 204 |
/// | DELETE | `/api/applications/{id}` | 204 |
/// | POST | `/api/applications/{id}/activate` | 200 [ApplicationResponse] |
/// | POST | `/api/applications/{id}/deactivate` | 200 [ApplicationResponse] |
/// | POST | `/api/applications/{id}/service-account` | 204 |
/// | GET | `/api/applications/{id}/clients` | 200 [ClientConfigListResponse] |
/// | GET | `/api/applications/{id}/clients/{clientId}` | 200 [ClientConfigResponse] |
/// | POST | `/api/applications/{id}/clients/{clientId}/enable` | 204 |
/// | POST | `/api/applications/{id}/clients/{clientId}/disable` | 204 |
/// | POST | `/api/applications/{id}/provision-service-account` | 201 [ApplicationProvisionServiceAccountResponse] |
/// | POST | `/api/applications/{id}/provision-login-client` | 201 [ApplicationProvisionLoginClientResponse] |
public final class ApplicationApi {

    private ApplicationApi() {
    }

    /// The handlers' dependencies. `roles` answers the roles listing only.
    /// `serviceAccounts` / `principals` / `oauthClients` / `encryption` back
    /// the two provisioning routes (spec §10) and `hasLoginClient` (spec §11
    /// q3).
    public record State(ApplicationRepository repo, ClientConfigRepository configs, RoleRepository roles, UnitOfWork uow,
                        ServiceAccountRepository serviceAccounts, PrincipalRepository principals,
                        OAuthClientRepository oauthClients, Optional<Encryption> encryption) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(configs, "configs");
            Objects.requireNonNull(roles, "roles");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(serviceAccounts, "serviceAccounts");
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(encryption, "encryption");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the
    /// lockfile's. Literal sub-paths are registered before the `{id}` ones.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/applications", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/applications", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/applications/by-code/{code}", Auth.scoped(ctx -> getByCode(ctx, s)));
        routes.get("/api/applications/by-id/{id}/roles", Auth.scoped(ctx -> listRoles(ctx, s)));
        routes.get("/api/applications/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.put("/api/applications/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.delete("/api/applications/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.post("/api/applications/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
        routes.post("/api/applications/{id}/deactivate", Auth.scoped(ctx -> deactivate(ctx, s)));
        routes.post("/api/applications/{id}/service-account", Auth.scoped(ctx -> attachServiceAccount(ctx, s)));
        routes.get("/api/applications/{id}/clients", Auth.scoped(ctx -> listClientConfigs(ctx, s)));
        routes.get("/api/applications/{id}/clients/{clientId}", Auth.scoped(ctx -> getClientConfig(ctx, s)));
        routes.post("/api/applications/{id}/clients/{clientId}/enable", Auth.scoped(ctx -> enableForClient(ctx, s)));
        routes.post("/api/applications/{id}/clients/{clientId}/disable", Auth.scoped(ctx -> disableForClient(ctx, s)));
        routes.post("/api/applications/{id}/provision-service-account", Auth.scoped(ctx -> provisionServiceAccount(ctx, s)));
        routes.post("/api/applications/{id}/provision-login-client", Auth.scoped(ctx -> provisionLoginClient(ctx, s)));
    }

    // ── Handlers: applications ─────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        Checks.require(Auth.current(), APPLICATION_VIEW);
        List<ApplicationResponse> items = s.repo().findWithFilters(listFilter(ctx)).stream()
                .map(a -> ApplicationResponse.from(a, s.oauthClients().hasLoginClientFor(a.id()))).toList();
        ctx.json(new ApplicationListResponse(items, items.size()));
    }

    private static void getById(Context ctx, State s) {
        Checks.require(Auth.current(), APPLICATION_VIEW);
        Application a = applicationById(s, ctx.pathParam("id"));
        ctx.json(ApplicationResponse.from(a, s.oauthClients().hasLoginClientFor(a.id())));
    }

    private static void getByCode(Context ctx, State s) {
        Checks.require(Auth.current(), APPLICATION_VIEW);
        Application a = applicationByCode(s, ctx.pathParam("code"));
        ctx.json(ApplicationResponse.from(a, s.oauthClients().hasLoginClientFor(a.id())));
    }

    private static void create(Context ctx, State s) {
        Checks.requireAny(Auth.current(), APPLICATION_CREATE, APPLICATION_UPDATE, APPLICATION_DELETE);
        var cmd = ctx.bodyAsClass(CreateApplicationRequest.class).toCommand();
        var event = CreateApplication.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.applicationId()));
    }

    private static void update(Context ctx, State s) {
        Checks.requireAny(Auth.current(), APPLICATION_CREATE, APPLICATION_UPDATE, APPLICATION_DELETE);
        var cmd = ctx.bodyAsClass(UpdateApplicationRequest.class).toCommand(ctx.pathParam("id"));
        UpdateApplication.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    /// Answers with the re-read application, as the lockfile says.
    private static void activate(Context ctx, State s) {
        Checks.requireAny(Auth.current(), APPLICATION_CREATE, APPLICATION_UPDATE, APPLICATION_DELETE);
        String id = ctx.pathParam("id");
        ActivateApplication.of(s.repo()).run(s.uow(), new ActivateCommand(id), Auth.executionContext());
        Application a = applicationById(s, id);
        ctx.json(ApplicationResponse.from(a, s.oauthClients().hasLoginClientFor(a.id())));
    }

    /// Answers with the re-read application, as the lockfile says.
    private static void deactivate(Context ctx, State s) {
        Checks.requireAny(Auth.current(), APPLICATION_CREATE, APPLICATION_UPDATE, APPLICATION_DELETE);
        String id = ctx.pathParam("id");
        DeactivateApplication.of(s.repo()).run(s.uow(), new DeactivateCommand(id), Auth.executionContext());
        Application a = applicationById(s, id);
        ctx.json(ApplicationResponse.from(a, s.oauthClients().hasLoginClientFor(a.id())));
    }

    private static void delete(Context ctx, State s) {
        Checks.require(Auth.current(), APPLICATION_DELETE);
        DeleteApplication.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void attachServiceAccount(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = ctx.bodyAsClass(AttachServiceAccountRequest.class).toCommand(ctx.pathParam("id"));
        AttachServiceAccount.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    /// Anchor-only (spec §10): creates + attaches a dedicated service account,
    /// its `SERVICE` principal and a `CONFIDENTIAL` OAuth client atomically.
    /// The response secret is the plaintext client secret, shown exactly once.
    private static void provisionServiceAccount(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var result = ProvisionServiceAccount.of(s.repo(), s.serviceAccounts(), s.principals(), s.oauthClients(), s.encryption())
                .run(s.uow(), new ProvisionServiceAccountCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(201).json(new ApplicationProvisionServiceAccountResponse("Service account provisioned",
                new ApplicationServiceAccountCredentials(result.principalId(), result.serviceAccountName(),
                        new ApplicationOAuthClientCredentials(result.oauthClientId(), result.oauthClientClientId(), result.oauthClientSecret()))));
    }

    /// Anchor-only (spec §10): a thin handler over `CreateOAuthClient`, not a
    /// new operation. `PUBLIC` (default) has no secret and PKCE required;
    /// `CONFIDENTIAL` returns a plaintext secret once.
    private static void provisionLoginClient(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var body = ctx.bodyAsClass(ProvisionLoginClientRequest.class);
        if (body.redirectUris() == null || body.redirectUris().isEmpty()) {
            throw UseCaseException.validation("REDIRECT_URIS_REQUIRED", "At least one redirect URI is required");
        }
        Application app = applicationById(s, ctx.pathParam("id"));
        String clientType = "CONFIDENTIAL".equals(body.clientType()) ? "CONFIDENTIAL" : "PUBLIC";

        var secret = new AtomicReference<String>();
        var cmd = new io.flowcatalyst.platform.oauthclient.operations.CreateCommand(null, app.name() + " Login", clientType,
                body.redirectUris(), null, List.of("authorization_code", "refresh_token"),
                List.of("openid", "profile", "email"), body.allowedOrigins(), List.of(app.id()), null, null, null, null);
        OAuthClientCreated event = CreateOAuthClient.of(s.oauthClients(), s.encryption(), secret::set)
                .run(s.uow(), cmd, Auth.executionContext());

        ctx.status(201).json(new ApplicationProvisionLoginClientResponse("Login client provisioned",
                new ApplicationLoginClientCredentials(clientType, body.redirectUris(),
                        new ApplicationOAuthClientCredentials(event.oauthClientId(), event.clientId(), secret.get()))));
    }

    // ── Handlers: client configs + roles ───────────────────────────────────

    private static void listClientConfigs(Context ctx, State s) {
        Checks.require(Auth.current(), APPLICATION_VIEW);
        ctx.json(new ClientConfigListResponse(s.configs().findByApplication(ctx.pathParam("id")).stream()
                .map(ClientConfigResponse::from).toList()));
    }

    private static void getClientConfig(Context ctx, State s) {
        Checks.require(Auth.current(), APPLICATION_VIEW);
        ctx.json(ClientConfigResponse.from(clientConfig(s, ctx.pathParam("id"), ctx.pathParam("clientId"))));
    }

    private static void enableForClient(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = new EnableForClientCommand(ctx.pathParam("id"), ctx.pathParam("clientId"));
        EnableApplicationForClient.of(s.repo(), s.configs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void disableForClient(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        var cmd = new DisableForClientCommand(ctx.pathParam("id"), ctx.pathParam("clientId"));
        DisableApplicationForClient.of(s.configs()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    /// Role names registered against the application (spec §3); `[]` for an unknown id.
    private static void listRoles(Context ctx, State s) {
        Checks.require(Auth.current(), APPLICATION_VIEW);
        ctx.json(new ApplicationRolesResponse(s.roles().findByApplicationId(ctx.pathParam("id")).stream().map(Role::name).toList()));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter: `type` is parsed leniently; `active` is
    /// `"true"` → active only, any other value → inactive only, absent → all
    /// (spec §3, open question 4).
    private static ListFilter listFilter(Context ctx) {
        String type = queryParam(ctx, "type");
        String active = queryParam(ctx, "active");
        return new ListFilter(type == null ? null : ApplicationType.parse(type), active == null ? null : "true".equals(active));
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Application applicationById(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("Application", id));
    }

    private static Application applicationByCode(State s, String code) {
        return s.repo().findByCode(code).orElseThrow(() -> HttpError.notFound("Application", code));
    }

    private static ClientConfig clientConfig(State s, String applicationId, String clientId) {
        return s.configs().findByApplicationAndClient(applicationId, clientId)
                .orElseThrow(() -> HttpError.notFound("ClientConfig", applicationId + ":" + clientId));
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/applications`.
    public record CreateApplicationRequest(String code, String name, String type, String description, String iconUrl,
                                           String website, String logo, String logoMimeType, String defaultBaseUrl) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, type, description, iconUrl, website, logo, logoMimeType, defaultBaseUrl);
        }
    }

    /// Body of `PUT /api/applications/{id}`; absent fields are left unchanged.
    public record UpdateApplicationRequest(String name, String description, String iconUrl, String website, String logo,
                                           String logoMimeType, String defaultBaseUrl) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description, iconUrl, website, logo, logoMimeType, defaultBaseUrl);
        }
    }

    /// Body of `POST /api/applications/{id}/service-account`.
    public record AttachServiceAccountRequest(String serviceAccountId, String serviceAccountCode) {
        public AttachServiceAccountCommand toCommand(String applicationId) {
            return new AttachServiceAccountCommand(applicationId, serviceAccountId, serviceAccountCode);
        }
    }

    /// The wire shape of one application; optional fields are omitted when
    /// `null`. `hasLoginClient` is resolved from the OAuth-client aggregate
    /// (spec §3, §11 q3, now implemented — see [OAuthClientRepository#hasLoginClientFor]).
    public record ApplicationResponse(
            String id,
            String type,
            String code,
            String name,
            String description,
            String iconUrl,
            String website,
            String logo,
            String logoMimeType,
            String defaultBaseUrl,
            String serviceAccountId,
            boolean active,
            boolean hasLoginClient,
            Instant createdAt,
            Instant updatedAt) {

        public static ApplicationResponse from(Application a, boolean hasLoginClient) {
            return new ApplicationResponse(a.id(), a.type().name(), a.code(), a.name(), a.description(), a.iconUrl(),
                    a.website(), a.logo(), a.logoMimeType(), a.defaultBaseUrl(), a.serviceAccountId(), a.active(),
                    hasLoginClient, a.createdAt(), a.updatedAt());
        }
    }

    /// `{"applications": [...], "total": n}`.
    public record ApplicationListResponse(List<ApplicationResponse> applications, int total) {
        public ApplicationListResponse {
            applications = applications == null ? List.of() : List.copyOf(applications);
        }
    }

    /// One client config on the wire; `baseUrlOverride` and `configJson` are
    /// in the schema but never populated (spec §1.2), so they are omitted.
    public record ClientConfigResponse(
            String id,
            String applicationId,
            String clientId,
            boolean enabled,
            String baseUrlOverride,
            JsonNode configJson,
            Instant createdAt,
            Instant updatedAt) {

        public static ClientConfigResponse from(ClientConfig c) {
            return new ClientConfigResponse(c.id(), c.applicationId(), c.clientId(), c.enabled(), null, null,
                    c.createdAt(), c.updatedAt());
        }
    }

    /// `{"items": [...]}`.
    public record ClientConfigListResponse(List<ClientConfigResponse> items) {
        public ClientConfigListResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /// `{"roles": ["app:role", …]}`.
    public record ApplicationRolesResponse(List<String> roles) {
        public ApplicationRolesResponse {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    // ── Wire DTOs: provisioning (spec §10) ──────────────────────────────────

    /// Body of `POST /api/applications/{id}/provision-login-client`.
    /// `allowedOrigins` is a **deliberate deviation from Go** (`docs/backlog.md`):
    /// Go declares the field on the request and never reads it (a defect);
    /// Java stores it on the created client.
    public record ProvisionLoginClientRequest(List<String> redirectUris, String clientType, List<String> allowedOrigins) {
    }

    /// One OAuth client's one-time credentials, shared by both provisioning
    /// responses. `clientSecret` is absent for a `PUBLIC` client (the schema
    /// does not require it).
    public record ApplicationOAuthClientCredentials(String id, String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "ApplicationOAuthClientCredentials[id=" + id + ", clientId=" + clientId + ", clientSecret=***]";
        }
    }

    /// `{principalId, name, oauthClient}` — `POST …/provision-service-account`'s
    /// `serviceAccount` member.
    public record ApplicationServiceAccountCredentials(String principalId, String name, ApplicationOAuthClientCredentials oauthClient) {
    }

    /// `{message, serviceAccount}` — `POST …/provision-service-account`'s body.
    public record ApplicationProvisionServiceAccountResponse(String message, ApplicationServiceAccountCredentials serviceAccount) {
    }

    /// `{clientType, redirectUris, oauthClient}` — `POST …/provision-login-client`'s
    /// `loginClient` member.
    public record ApplicationLoginClientCredentials(String clientType, List<String> redirectUris, ApplicationOAuthClientCredentials oauthClient) {
        public ApplicationLoginClientCredentials {
            redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        }
    }

    /// `{message, loginClient}` — `POST …/provision-login-client`'s body.
    public record ApplicationProvisionLoginClientResponse(String message, ApplicationLoginClientCredentials loginClient) {
    }
}

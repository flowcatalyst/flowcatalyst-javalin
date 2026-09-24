package io.flowcatalyst.platform.serviceaccount.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.flowcatalyst.platform.serviceaccount.operations.MintServiceAccountTokenCommand;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountTokenMinted;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.RoleAssignment;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.WebhookAuthType;
import io.flowcatalyst.platform.serviceaccount.WebhookCredentials;
import io.flowcatalyst.platform.serviceaccount.operations.AssignRolesCommand;
import io.flowcatalyst.platform.serviceaccount.operations.AssignRolesToServiceAccount;
import io.flowcatalyst.platform.serviceaccount.operations.CreateCommand;
import io.flowcatalyst.platform.serviceaccount.operations.CreateServiceAccountWithCredentials;
import io.flowcatalyst.platform.serviceaccount.operations.DeactivateCommand;
import io.flowcatalyst.platform.serviceaccount.operations.DeactivateServiceAccount;
import io.flowcatalyst.platform.serviceaccount.operations.DeleteCommand;
import io.flowcatalyst.platform.serviceaccount.operations.DeleteServiceAccount;
import io.flowcatalyst.platform.serviceaccount.operations.MintServiceAccountToken;
import io.flowcatalyst.platform.serviceaccount.operations.RegenerateAuthToken;
import io.flowcatalyst.platform.serviceaccount.operations.RegenerateAuthTokenCommand;
import io.flowcatalyst.platform.serviceaccount.operations.RegenerateSigningSecret;
import io.flowcatalyst.platform.serviceaccount.operations.RegenerateSigningSecretCommand;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountTokenMinter;
import io.flowcatalyst.platform.serviceaccount.operations.UpdateCommand;
import io.flowcatalyst.platform.serviceaccount.operations.UpdateServiceAccount;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.flowcatalyst.platform.shared.auth.Permission.SERVICE_ACCOUNT_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.SERVICE_ACCOUNT_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.SERVICE_ACCOUNT_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.SERVICE_ACCOUNT_VIEW;

/// The `/api/service-accounts` surface (spec §3). A write handler does
/// exactly: coarse permission → command from DTO → `Operation.run` →
/// response; role-assignment and token-mint are gated **anchor reach AND a
/// permission** (spec §3: "both hand out authority rather than editing a
/// record"; the permission since security-fixes S1.2 — the tier is reach,
/// never authority). Every handler runs inside [Auth#scoped] so operations can read
/// [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/service-accounts` | 200 [ServiceAccountListResponse] |
/// | POST | `/api/service-accounts` | 201 [CreateServiceAccountResponse] |
/// | GET | `/api/service-accounts/code/{code}` | 200 [ServiceAccountResponse]; 404 |
/// | GET | `/api/service-accounts/{id}` | 200; 404 |
/// | PUT | `/api/service-accounts/{id}` | 204 |
/// | POST | `/api/service-accounts/{id}/deactivate` | 204 |
/// | DELETE | `/api/service-accounts/{id}` | 204 |
/// | GET | `/api/service-accounts/{id}/roles` | 200 [ServiceAccountRoleListResponse] |
/// | PUT | `/api/service-accounts/{id}/roles` | 200 [ServiceAccountRolesAssignedResponse] (anchor + `SERVICE_ACCOUNT_UPDATE`) |
/// | POST | `/api/service-accounts/{id}/regenerate-token` (+`regenerate-auth-token` alias) | 200 [RegenerateAuthTokenResponse] |
/// | POST | `/api/service-accounts/{id}/regenerate-secret` (+`regenerate-signing-secret` alias) | 200 [RegenerateSigningSecretResponse] |
/// | POST | `/api/service-accounts/{id}/token` | 200 [ServiceAccountTokenResponse] (anchor + `SERVICE_ACCOUNT_UPDATE`) |
///
/// `POST /api/service-accounts/{id}/token`'s "best-effort audit row: who
/// obtained a credential for which account" (spec §8 step 8) is written
/// since owner ruling 2026-09-06 #15: the mint emits
/// [ServiceAccountEvents.ServiceAccountTokenMinted] through the unit of work
/// under [MintServiceAccountTokenCommand], which lands the event and the
/// audit row in one transaction — after the token is minted, and swallowed
/// (logged) on failure, so a broken audit path never refuses a credential.
///
/// `POST /api/service-accounts`'s `oauth` field (spec §4.1, §8) is now a real,
/// persisted `CONFIDENTIAL` OAuth client — see
/// [io.flowcatalyst.platform.serviceaccount.operations.CreateServiceAccountWithCredentials].
public final class ServiceAccountApi {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceAccountApi.class);

    private ServiceAccountApi() {
    }

    /// The handlers' dependencies.
    ///
    /// @param oauthClients       where `create` mints the account's `CONFIDENTIAL` OAuth client (spec §4.1, §8)
    /// @param clients            validates `clientIds` on create/update (service-account-reach.md §1)
    /// @param encryption         encrypts that client's secret at rest; empty ⇒ `create` fails internal `SECRET`
    /// @param minter             mints the `POST /{id}/token` bearer; `null` disables that endpoint (fail closed, spec §8 step 2)
    /// @param flattenPermissions role names → permission ceiling for the token mint; `null` mints with no scope claim
    public record State(ServiceAccountRepository repo, PrincipalRepository principals, UnitOfWork uow,
                        OAuthClientRepository oauthClients, ClientRepository clients, Optional<Encryption> encryption,
                        ServiceAccountTokenMinter minter, Function<List<String>, List<String>> flattenPermissions) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(encryption, "encryption");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the
    /// lockfile's. The two alias pairs (spec §3) share one handler each.
    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/service-accounts", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/service-accounts", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/service-accounts/code/{code}", Auth.scoped(ctx -> getByCode(ctx, s)));
        routes.get("/api/service-accounts/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        write.put("/api/service-accounts/{id}", Auth.scoped(ctx -> update(ctx, s)));
        write.post("/api/service-accounts/{id}/deactivate", Auth.scoped(ctx -> deactivate(ctx, s)));
        write.delete("/api/service-accounts/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.get("/api/service-accounts/{id}/roles", Auth.scoped(ctx -> listRoles(ctx, s)));
        write.put("/api/service-accounts/{id}/roles", Auth.scoped(ctx -> assignRoles(ctx, s)));

        Handler regenerateAuthToken = Auth.scoped(ctx -> regenerateAuthToken(ctx, s));
        write.post("/api/service-accounts/{id}/regenerate-token", regenerateAuthToken);
        write.post("/api/service-accounts/{id}/regenerate-auth-token", regenerateAuthToken);

        Handler regenerateSigningSecret = Auth.scoped(ctx -> regenerateSigningSecret(ctx, s));
        write.post("/api/service-accounts/{id}/regenerate-secret", regenerateSigningSecret);
        write.post("/api/service-accounts/{id}/regenerate-signing-secret", regenerateSigningSecret);

        write.post("/api/service-accounts/{id}/token", Auth.scoped(ctx -> mintToken(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        Checks.require(Auth.current(), SERVICE_ACCOUNT_VIEW);
        List<ServiceAccountResponse> items = s.repo().findAll().stream().map(sa -> ServiceAccountResponse.from(sa, null, null)).toList();
        ctx.json(new ServiceAccountListResponse(items, items.size()));
    }

    private static void getByCode(Exchange ctx, State s) {
        Checks.require(Auth.current(), SERVICE_ACCOUNT_VIEW);
        String code = ctx.pathParam("code");
        ServiceAccount sa = s.repo().findByCode(code).orElseThrow(() -> HttpError.notFound("ServiceAccount", code));
        // principalId/oauthClientId omitted here, matching Go (spec §9.1, §10 Q5;
        // login-attempt-links.md B1) — only the single-id read below populates them.
        ctx.json(ServiceAccountResponse.from(sa, null, null));
    }

    private static void getById(Exchange ctx, State s) {
        Checks.require(Auth.current(), SERVICE_ACCOUNT_VIEW);
        String id = ctx.pathParam("id");
        ServiceAccount sa = s.repo().findById(id).orElseThrow(() -> HttpError.notFound("ServiceAccount", id));
        String principalId = principalIdOf(s, sa.id());
        ctx.json(ServiceAccountResponse.from(sa, principalId, oauthClientIdOf(s, principalId)));
    }

    private static void create(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SERVICE_ACCOUNT_CREATE, SERVICE_ACCOUNT_UPDATE, SERVICE_ACCOUNT_DELETE);
        var cmd = ctx.bodyAsClass(CreateServiceAccountRequest.class).toCommand();
        var result = CreateServiceAccountWithCredentials.of(s.repo(), s.principals(), s.oauthClients(), s.clients(), s.encryption())
                .run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreateServiceAccountResponse(
                // principalId/oauthClientId omitted on the nested serviceAccount, matching Go (spec
                // §9.1, §10 Q5; login-attempt-links.md B1) — the top-level principalId field below is
                // the one Go always populates here, and the create response never surfaces oauthClientId.
                ServiceAccountResponse.from(result.serviceAccount(), null, null),
                result.principalId(),
                new ServiceAccountOAuthSecrets(result.oauthClientClientId(), result.oauthClientSecret()),
                new ServiceAccountWebhookSecrets(result.authToken(), result.signingSecret())));
    }

    private static void update(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SERVICE_ACCOUNT_CREATE, SERVICE_ACCOUNT_UPDATE, SERVICE_ACCOUNT_DELETE);
        var cmd = ctx.bodyAsClass(UpdateServiceAccountRequest.class).toCommand(ctx.pathParam("id"));
        UpdateServiceAccount.of(s.repo(), s.principals(), s.clients()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void deactivate(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SERVICE_ACCOUNT_CREATE, SERVICE_ACCOUNT_UPDATE, SERVICE_ACCOUNT_DELETE);
        DeactivateServiceAccount.of(s.repo()).run(s.uow(), new DeactivateCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), SERVICE_ACCOUNT_DELETE);
        DeleteServiceAccount.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void listRoles(Exchange ctx, State s) {
        Checks.require(Auth.current(), SERVICE_ACCOUNT_VIEW);
        String id = ctx.pathParam("id");
        s.repo().findById(id).orElseThrow(() -> HttpError.notFound("ServiceAccount", id));
        ctx.json(new ServiceAccountRoleListResponse(rolesOf(s, id).stream().map(RoleAssignmentResponse::from).toList()));
    }

    /// Anchor reach (spec §3) AND `SERVICE_ACCOUNT_UPDATE` (security-fixes
    /// S1.2): role assignment grants authority in the `principal` aggregate,
    /// and the anchor tier is reach, never authority — without the permission
    /// an application's own anchor-reach service account could assign itself
    /// `platform:super-admin`.
    private static void assignRoles(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), SERVICE_ACCOUNT_UPDATE);
        String id = ctx.pathParam("id");
        var body = ctx.bodyAsClass(AssignRolesRequest.class);
        var event = AssignRolesToServiceAccount.of(s.repo(), s.principals())
                .run(s.uow(), new AssignRolesCommand(id, body.roles()), Auth.executionContext());
        var roles = rolesOf(s, id).stream().map(RoleAssignmentResponse::from).toList();
        ctx.json(new ServiceAccountRolesAssignedResponse(roles, event.rolesAdded(), event.rolesRemoved()));
    }

    /// Permission-gated, NOT anchor-only (spec §3 explicitly names only
    /// role-assignment and token-mint as the anchor-only pair) — this diverges
    /// from Go, which anchor-gates rotation too; the spec wins (CONVENTIONS §8).
    private static void regenerateAuthToken(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SERVICE_ACCOUNT_CREATE, SERVICE_ACCOUNT_UPDATE, SERVICE_ACCOUNT_DELETE);
        String id = ctx.pathParam("id");
        // The sink is a local: the plaintext cannot outlive this request, and is
        // only read below, on the success path — a failed commit discloses nothing.
        var token = new AtomicReference<String>();
        RegenerateAuthToken.of(s.repo(), token::set).run(s.uow(), new RegenerateAuthTokenCommand(id), Auth.executionContext());
        ctx.json(new RegenerateAuthTokenResponse(id, token.get()));
    }

    private static void regenerateSigningSecret(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SERVICE_ACCOUNT_CREATE, SERVICE_ACCOUNT_UPDATE, SERVICE_ACCOUNT_DELETE);
        String id = ctx.pathParam("id");
        var secret = new AtomicReference<String>();
        RegenerateSigningSecret.of(s.repo(), secret::set).run(s.uow(), new RegenerateSigningSecretCommand(id), Auth.executionContext());
        ctx.json(new RegenerateSigningSecretResponse(id, secret.get()));
    }

    /// Anchor reach + `SERVICE_ACCOUNT_UPDATE` (security-fixes S1.2: the
    /// caller obtains a bearer that acts as the account — a credential, so a
    /// permission, not the tier alone), best-effort-audited (spec §3, §8;
    /// owner ruling 2026-09-06 #15).
    private static void mintToken(Exchange ctx, State s) {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), SERVICE_ACCOUNT_UPDATE);
        String id = ctx.pathParam("id");
        var result = MintServiceAccountToken.mint(s.repo(), s.principals(), s.minter(), s.flattenPermissions(), id);
        // A bearer was handed out for this account — a use of its credentials
        // (spec §9.2). Best-effort: a failed stamp must never fail the mint.
        s.repo().touchLastUsed(id);
        // Spec §8 step 8: who obtained a credential for which account — never the
        // token. Best-effort too: the credential is already minted, so an audit
        // failure is logged, not answered.
        try {
            var ec = Auth.executionContext();
            s.uow().emitEvent(ServiceAccountTokenMinted.of(ec, id, principalIdOf(s, id), result.expiresInSeconds(),
                    result.permissions()), new MintServiceAccountTokenCommand(id));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("service-account token mint was not audited")
                    .addKeyValue("id", id)
                    .setCause(e)
                    .log();
        }
        String scope = result.permissions().isEmpty() ? null : String.join(" ", result.permissions());
        ctx.json(new ServiceAccountTokenResponse(result.accessToken(), "Bearer", result.expiresInSeconds(), scope));
    }

    // ── Read-side helpers ────────────────────────────────────────────────────

    /// The linked `SERVICE` principal's id, or `null` when there is none —
    /// surfaced so the UI can manage this account's application access via
    /// `/api/principals/{id}/application-access` (spec §7 lets `getById`
    /// resolve it, matching Go).
    private static String principalIdOf(State s, String serviceAccountId) {
        return s.principals().findByServiceAccount(serviceAccountId).map(p -> p.id()).orElse(null);
    }

    /// The public `client_id` (never the OAuth client row's own `id`) of the
    /// OAuth client provisioned for `principalId`'s `client_credentials`
    /// grant — the earliest by `(created_at, id)` when several exist, `null`
    /// when none is linked or there is no linked principal at all
    /// (`docs/spec/login-attempt-links.md` B1).
    private static String oauthClientIdOf(State s, String principalId) {
        if (principalId == null) return null;
        return s.oauthClients().findByPrincipalId(principalId).stream().findFirst().map(OAuthClient::clientId).orElse(null);
    }

    /// The linked principal's roles, or `[]` when there is no linked principal.
    private static List<RoleAssignment> rolesOf(State s, String serviceAccountId) {
        return s.repo().findById(serviceAccountId)
                .map(ServiceAccount::roles)
                .orElse(List.of());
    }

    // ── Wire DTOs (lockfile components) ─────────────────────────────────────

    /// Body of `POST /api/service-accounts`. `webhookCredentials` is accepted
    /// and validated (an unknown `authType` still rejects, see
    /// [WebhookCredentialsDto#toEntity]) but never used — see [CreateCommand].
    public record CreateServiceAccountRequest(String code, String name, String description, String scope,
                                               List<String> clientIds, String applicationId,
                                               WebhookCredentialsDto webhookCredentials) {
        public CreateCommand toCommand() {
            return new CreateCommand(code, name, description, scope, clientIds, applicationId,
                    webhookCredentials == null ? null : webhookCredentials.toEntity());
        }
    }

    /// Body of `PUT /api/service-accounts/{id}`.
    public record UpdateServiceAccountRequest(String name, String description, String scope, List<String> clientIds,
                                               WebhookCredentialsDto webhookCredentials) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description, scope, clientIds,
                    webhookCredentials == null ? null : webhookCredentials.toEntity());
        }
    }

    /// Body of `PUT /api/service-accounts/{id}/roles`.
    public record AssignRolesRequest(List<String> roles) {
        public AssignRolesRequest {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /// Mirrors [WebhookCredentials] on the wire (spec §2.1). `authType` is
    /// read strictly — an unrecognised value 400s `INVALID_AUTH_TYPE` rather
    /// than silently becoming `NONE` (spec §11, X-06).
    public record WebhookCredentialsDto(String authType, String token, String username, String password,
                                        String headerName, String signingSecret, String signingAlgorithm,
                                        String signatureHeader) {
        /// @throws UseCaseException validation `INVALID_AUTH_TYPE`
        public WebhookCredentials toEntity() {
            WebhookAuthType type;
            try {
                type = WebhookAuthType.parse(authType);
            } catch (WebhookAuthType.UnrecognisedAuthTypeException e) {
                throw UseCaseException.validation("INVALID_AUTH_TYPE", "unknown webhook auth type \"" + authType + "\"");
            }
            return new WebhookCredentials(type, token, username, password, headerName, signingSecret, signingAlgorithm, signatureHeader);
        }
    }

    /// One role assignment on the wire (spec §2.2).
    public record RoleAssignmentResponse(String roleName, String clientId, String assignmentSource, Instant assignedAt, String assignedBy) {
        public static RoleAssignmentResponse from(RoleAssignment ra) {
            return new RoleAssignmentResponse(ra.roleName(), ra.clientId(), ra.assignmentSource(), ra.assignedAt(), ra.assignedBy());
        }
    }

    /// The wire shape of one service account (spec §3): flat, `authType`
    /// hoisted out of the webhook credentials, `roles` as a plain name list.
    /// Webhook secrets are never exposed here — only once, at create/rotate
    /// time. `principalId` is populated on the single-account reads only
    /// (spec §9.1, matches the `roles` hydration decision). `oauthClientId`
    /// is the public `client_id` (never the OAuth client row's own `id`) of
    /// the OAuth client provisioned for that principal's `client_credentials`
    /// grant, populated on the same single-account reads
    /// (`docs/spec/login-attempt-links.md` B1) — absent when none is linked.
    public record ServiceAccountResponse(String id, String code, String name, String description, boolean active,
                                         List<String> clientIds, String scope, String applicationId, String authType,
                                         List<String> roles, String principalId, String oauthClientId,
                                         Instant lastUsedAt, Instant createdAt, Instant updatedAt) {
        public static ServiceAccountResponse from(ServiceAccount sa, String principalId, String oauthClientId) {
            return new ServiceAccountResponse(sa.id(), sa.code(), sa.name(), sa.description(), sa.active(),
                    sa.clientIds(), sa.scope(), sa.applicationId(), sa.webhookCredentials().authType().name(),
                    sa.roles().stream().map(RoleAssignment::roleName).toList(), principalId, oauthClientId,
                    sa.lastUsedAt(), sa.createdAt(), sa.updatedAt());
        }
    }

    /// `{"serviceAccounts": [...], "total": n}`.
    public record ServiceAccountListResponse(List<ServiceAccountResponse> serviceAccounts, long total) {
        public ServiceAccountListResponse {
            serviceAccounts = serviceAccounts == null ? List.of() : List.copyOf(serviceAccounts);
        }
    }

    public record ServiceAccountRoleListResponse(List<RoleAssignmentResponse> roles) {
        public ServiceAccountRoleListResponse {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    public record ServiceAccountRolesAssignedResponse(List<RoleAssignmentResponse> roles, List<String> addedRoles, List<String> removedRoles) {
        public ServiceAccountRolesAssignedResponse {
            roles = roles == null ? List.of() : List.copyOf(roles);
            addedRoles = addedRoles == null ? List.of() : List.copyOf(addedRoles);
            removedRoles = removedRoles == null ? List.of() : List.copyOf(removedRoles);
        }
    }

    /// The one-time OAuth client credentials on `POST /api/service-accounts`
    /// (spec §5, §8) — `clientId` is the newly minted `CONFIDENTIAL` client's
    /// OAuth2 `client_id`, `clientSecret` its plaintext, returned once.
    public record ServiceAccountOAuthSecrets(String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "ServiceAccountOAuthSecrets[clientId=" + clientId + ", clientSecret=***]";
        }
    }

    /// The one-time webhook credentials on `POST /api/service-accounts` (spec §5).
    public record ServiceAccountWebhookSecrets(String authToken, String signingSecret) {
        @Override
        public String toString() {
            return "ServiceAccountWebhookSecrets[authToken=***, signingSecret=***]";
        }
    }

    public record CreateServiceAccountResponse(ServiceAccountResponse serviceAccount, String principalId,
                                               ServiceAccountOAuthSecrets oauth, ServiceAccountWebhookSecrets webhook) {
    }

    /// `authToken` is present only on a successful rotation (spec §4.6).
    public record RegenerateAuthTokenResponse(String id, String authToken) {
    }

    public record RegenerateSigningSecretResponse(String id, String signingSecret) {
    }

    /// `scope` is omitted when the mint carried no permissions (spec §8).
    public record ServiceAccountTokenResponse(String accessToken, String tokenType, long expiresIn, String scope) {
    }
}

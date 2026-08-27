package io.flowcatalyst.platform.principal.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.principal.AnchorDomains;
import io.flowcatalyst.platform.principal.ClientAccessGrant;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.EmailAddress;
import io.flowcatalyst.platform.principal.InviteEmailer;
import io.flowcatalyst.platform.principal.MfaService;
import io.flowcatalyst.platform.principal.Notifier;
import io.flowcatalyst.platform.principal.PasswordResetEmailer;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.principal.UserScopeDerivation;
import io.flowcatalyst.platform.principal.operations.Access;
import io.flowcatalyst.platform.principal.operations.ActivateCommand;
import io.flowcatalyst.platform.principal.operations.ActivateUser;
import io.flowcatalyst.platform.principal.operations.AssignApplicationAccess;
import io.flowcatalyst.platform.principal.operations.AssignApplicationAccessCommand;
import io.flowcatalyst.platform.principal.operations.AssignRoles;
import io.flowcatalyst.platform.principal.operations.AssignRolesCommand;
import io.flowcatalyst.platform.principal.operations.ClientAssociationMode;
import io.flowcatalyst.platform.principal.operations.CreateCommand;
import io.flowcatalyst.platform.principal.operations.CreateUser;
import io.flowcatalyst.platform.principal.operations.DeactivateCommand;
import io.flowcatalyst.platform.principal.operations.DeactivateUser;
import io.flowcatalyst.platform.principal.operations.DeleteCommand;
import io.flowcatalyst.platform.principal.operations.DeleteUser;
import io.flowcatalyst.platform.principal.operations.DeveloperSecrets;
import io.flowcatalyst.platform.principal.operations.GrantClientAccess;
import io.flowcatalyst.platform.principal.operations.GrantClientAccessCommand;
import io.flowcatalyst.platform.principal.operations.ResetPassword;
import io.flowcatalyst.platform.principal.operations.ResetPasswordCommand;
import io.flowcatalyst.platform.principal.operations.RevokeClientAccess;
import io.flowcatalyst.platform.principal.operations.RevokeClientAccessCommand;
import io.flowcatalyst.platform.principal.operations.RevokeDeveloperCredential;
import io.flowcatalyst.platform.principal.operations.RevokeDeveloperCredentialCommand;
import io.flowcatalyst.platform.principal.operations.SendPasswordReset;
import io.flowcatalyst.platform.principal.operations.SendPasswordResetCommand;
import io.flowcatalyst.platform.principal.operations.SetClientAssociation;
import io.flowcatalyst.platform.principal.operations.SetClientAssociationCommand;
import io.flowcatalyst.platform.principal.operations.SetDeveloperCredential;
import io.flowcatalyst.platform.principal.operations.SetDeveloperCredentialCommand;
import io.flowcatalyst.platform.principal.operations.SyncPrincipalInput;
import io.flowcatalyst.platform.principal.operations.SyncPrincipals;
import io.flowcatalyst.platform.principal.operations.SyncPrincipalsCommand;
import io.flowcatalyst.platform.principal.operations.UpdateCommand;
import io.flowcatalyst.platform.principal.operations.UpdateUser;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.apicommon.StatusChangeResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.flowcatalyst.platform.shared.auth.Permission.USER_ASSIGN_ROLES;
import static io.flowcatalyst.platform.shared.auth.Permission.USER_CREATE;
import static io.flowcatalyst.platform.shared.auth.Permission.USER_DELETE;
import static io.flowcatalyst.platform.shared.auth.Permission.USER_MANAGE;
import static io.flowcatalyst.platform.shared.auth.Permission.USER_UPDATE;
import static io.flowcatalyst.platform.shared.auth.Permission.USER_VIEW;

/// The `/api/principals` surface (spec §3). A write handler does: coarse
/// permission → command from DTO → `Operation.run` → response; the
/// command-shaping a non-anchor administrator is subject to (spec §5.3) and
/// the read-side rules are private helpers here. Every handler runs inside
/// [Auth#scoped] so the operations can read [Auth#current()].
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/principals` | 200 [PrincipalListResponse] |
/// | POST | `/api/principals` | 201 [CreatedResponse] |
/// | POST | `/api/principals/users` | 200 [PrincipalResponse] |
/// | POST | `/api/principals/bulk-import` | 200 [BulkImportResponse] |
/// | POST | `/api/principals/sync` | 200 [SyncUsersResponse] |
/// | GET | `/api/principals/check-email-domain` | 200 [CheckEmailDomainResponse] |
/// | GET | `/api/principals/developer-users` | 200 [DeveloperUserListResponse] |
/// | GET | `/api/principals/{id}` | 200 [PrincipalResponse] |
/// | GET | `/api/principals/{id}/version` | 200 [PrincipalVersionResponse] |
/// | PUT | `/api/principals/{id}` | 200 [PrincipalResponse] |
/// | POST | `/api/principals/{id}/activate` · `/deactivate` · `/reset-password` · `/send-password-reset` · `/reset-2fa` | 200 [StatusChangeResponse] |
/// | DELETE | `/api/principals/{id}` | 204 |
/// | GET/PUT/POST | `/api/principals/{id}/roles` · DELETE `/roles/{role}` | 200 |
/// | GET/PUT | `/api/principals/{id}/application-access` · GET `/available-applications` | 200 |
/// | GET/POST | `/api/principals/{id}/client-access` · DELETE `/client-access/{clientId}` | 200 / 204 |
/// | PUT | `/api/principals/{id}/client-association` | 200 [PrincipalResponse] |
/// | POST/DELETE | `/api/principals/{id}/developer-credential` | 200 [SetDeveloperCredentialResponse] / 204 |
public final class PrincipalApi {

    private static final Logger LOG = LoggerFactory.getLogger(PrincipalApi.class);

    /// Bulk import's row cap.
    static final int BULK_IMPORT_MAX_ROWS = 1000;

    private PrincipalApi() {
    }

    /// The handlers' dependencies (spec §10 for the stubbed ones).
    public record State(
            PrincipalRepository repo,
            ClientAccessGrantRepository grants,
            RoleRepository roles,
            ApplicationRepository applications,
            ClientConfigRepository clientConfigs,
            ClientRepository clients,
            EmailDomainMappingRepository mappings,
            IdentityProviderRepository identityProviders,
            AnchorDomains anchorDomains,
            PasswordResetEmailer passwordEmailer,
            InviteEmailer inviteEmailer,
            Notifier notifier,
            MfaService mfa,
            DeveloperSecrets developerSecrets,
            UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(grants, "grants");
            Objects.requireNonNull(roles, "roles");
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(clientConfigs, "clientConfigs");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(mappings, "mappings");
            Objects.requireNonNull(identityProviders, "identityProviders");
            Objects.requireNonNull(anchorDomains, "anchorDomains");
            Objects.requireNonNull(passwordEmailer, "passwordEmailer");
            Objects.requireNonNull(inviteEmailer, "inviteEmailer");
            Objects.requireNonNull(notifier, "notifier");
            Objects.requireNonNull(mfa, "mfa");
            Objects.requireNonNull(developerSecrets, "developerSecrets");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/principals", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/principals", Auth.scoped(ctx -> create(ctx, s)));
        routes.post("/api/principals/users", Auth.scoped(ctx -> createUser(ctx, s)));
        routes.post("/api/principals/bulk-import", Auth.scoped(ctx -> bulkImport(ctx, s)));
        routes.post("/api/principals/sync", Auth.scoped(ctx -> syncUsers(ctx, s)));
        routes.get("/api/principals/check-email-domain", Auth.scoped(ctx -> checkEmailDomain(ctx, s)));
        routes.get("/api/principals/developer-users", Auth.scoped(ctx -> listDeveloperUsers(ctx, s)));
        routes.get("/api/principals/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        routes.get("/api/principals/{id}/version", Auth.scoped(ctx -> getVersion(ctx, s)));
        routes.put("/api/principals/{id}", Auth.scoped(ctx -> update(ctx, s)));
        routes.post("/api/principals/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
        routes.post("/api/principals/{id}/deactivate", Auth.scoped(ctx -> deactivate(ctx, s)));
        routes.post("/api/principals/{id}/reset-password", Auth.scoped(ctx -> resetPassword(ctx, s)));
        routes.post("/api/principals/{id}/send-password-reset", Auth.scoped(ctx -> sendPasswordReset(ctx, s)));
        routes.post("/api/principals/{id}/reset-2fa", Auth.scoped(ctx -> resetTwoFactor(ctx, s)));
        routes.delete("/api/principals/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        routes.get("/api/principals/{id}/roles", Auth.scoped(ctx -> listRoles(ctx, s)));
        routes.put("/api/principals/{id}/roles", Auth.scoped(ctx -> assignRoles(ctx, s)));
        routes.post("/api/principals/{id}/roles", Auth.scoped(ctx -> addRole(ctx, s)));
        routes.delete("/api/principals/{id}/roles/{role}", Auth.scoped(ctx -> removeRole(ctx, s)));
        routes.get("/api/principals/{id}/application-access", Auth.scoped(ctx -> listApplicationAccess(ctx, s)));
        routes.put("/api/principals/{id}/application-access", Auth.scoped(ctx -> assignApplicationAccess(ctx, s)));
        routes.get("/api/principals/{id}/available-applications", Auth.scoped(ctx -> listAvailableApplications(ctx, s)));
        routes.get("/api/principals/{id}/client-access", Auth.scoped(ctx -> listClientAccess(ctx, s)));
        routes.post("/api/principals/{id}/client-access", Auth.scoped(ctx -> grantClientAccess(ctx, s)));
        routes.delete("/api/principals/{id}/client-access/{clientId}", Auth.scoped(ctx -> revokeClientAccess(ctx, s)));
        routes.put("/api/principals/{id}/client-association", Auth.scoped(ctx -> setClientAssociation(ctx, s)));
        routes.post("/api/principals/{id}/developer-credential", Auth.scoped(ctx -> setDeveloperCredential(ctx, s)));
        routes.delete("/api/principals/{id}/developer-credential", Auth.scoped(ctx -> revokeDeveloperCredential(ctx, s)));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, USER_VIEW);
        ListQuery q = ListQuery.from(ctx);
        List<Principal> matched = s.repo().findAll().stream()
                .filter(p -> ac.isAnchor() || (p.clientId() != null && ac.canAccessClient(p.clientId())))
                .filter(q::matches)
                .sorted(q.order())
                .toList();
        List<PrincipalResponse> page = q.page(matched).stream().map(PrincipalResponse::from).toList();
        ctx.json(new PrincipalListResponse(page, matched.size()));
    }

    /// Any principal may read its own record (the Profile page's developer
    /// credential status needs this without `USER_VIEW`); anyone else's needs
    /// the permission and, for a client-homed target, access to that client.
    private static void getById(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String id = ctx.pathParam("id");
        boolean self = isSelf(ac, id);
        if (!self) Checks.require(ac, USER_VIEW);
        Principal p = principal(s, id);
        if (!self && p.clientId() != null && !ac.canAccessClient(p.clientId())) {
            throw HttpError.forbidden("No access to this principal");
        }
        List<String> twoFactor = s.mfa().configured() ? s.mfa().confirmedMethods(p.id()) : null;
        ctx.json(PrincipalResponse.from(p, twoFactor));
    }

    /// When the principal (or a role it holds) last changed — an SDK's
    /// revocation check. Self needs no permission; anyone else's needs `USER_VIEW`.
    private static void getVersion(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String id = ctx.pathParam("id");
        if (!isSelf(ac, id)) Checks.require(ac, USER_VIEW);
        Instant at = s.repo().lookupVersion(id).orElseThrow(() -> HttpError.notFound("Principal", id));
        ctx.json(new PrincipalVersionResponse(at));
    }

    private static void listDeveloperUsers(Context ctx, State s) {
        Checks.require(Auth.current(), USER_VIEW);
        List<PrincipalResponse> out = s.repo().findByRole(SetDeveloperCredential.DEVELOPER_ROLE).stream()
                .map(PrincipalResponse::from).toList();
        ctx.json(new DeveloperUserListResponse(out, out.size()));
    }

    private static void listRoles(Context ctx, State s) {
        Checks.require(Auth.current(), USER_VIEW);
        Principal p = principal(s, ctx.pathParam("id"));
        ctx.json(new PrincipalRoleListResponse(PrincipalRoleAssignmentDTO.listFor(p)));
    }

    private static void listApplicationAccess(Context ctx, State s) {
        Checks.require(Auth.current(), USER_VIEW);
        Principal p = principal(s, ctx.pathParam("id"));
        List<ApplicationAccessResponse> apps = resolveApplications(s, p.accessibleApplicationIds());
        ctx.json(new ApplicationAccessListResponse(apps, apps.size(), p.allApplications()));
    }

    /// Every active application; for a non-anchor administrator only those the
    /// target's home client is entitled to (spec §5.3).
    private static void listAvailableApplications(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, USER_VIEW);
        Principal p = principal(s, ctx.pathParam("id"));
        Set<String> allowed = ac.isAnchor() ? null : clientApplicationIds(s, p.clientId());
        List<PrincipalAvailableApplication> out = s.applications()
                .findWithFilters(new ApplicationRepository.ListFilter(null, true)).stream()
                .filter(a -> allowed == null || allowed.contains(a.id()))
                .map(a -> new PrincipalAvailableApplication(a.id(), a.code(), a.name()))
                .toList();
        ctx.json(new PrincipalAvailableApplicationsResponse(out));
    }

    private static void listClientAccess(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        ctx.json(new ClientAccessGrantListResponse(s.grants().findByPrincipal(ctx.pathParam("id")).stream()
                .map(ClientAccessGrantResponse::from).toList()));
    }

    private static void checkEmailDomain(Context ctx, State s) {
        Checks.require(Auth.current(), USER_VIEW);
        String email = EmailAddress.normalise(ctx.queryParam("email"));
        if (email.isEmpty()) throw HttpError.badRequest("EMAIL_REQUIRED", "email query param is required");
        String domain = domainOrBadRequest(email);
        boolean emailExists = s.repo().findByEmail(email).isPresent();
        boolean isAnchorDomain = s.anchorDomains().contains(domain);
        EmailDomainMapping mapping = s.mappings().findByEmailDomain(domain).orElse(null);
        IdentityProvider idp = mapping == null ? null : s.identityProviders().findById(mapping.identityProviderId()).orElse(null);
        String idpType = idp == null ? IdentityProviderType.INTERNAL.name() : idp.type().name();
        boolean external = idp != null && idp.type() == IdentityProviderType.OIDC;
        UserScope scope = UserScopeDerivation.forDomain(isAnchorDomain, mapping);
        ctx.json(new CheckEmailDomainResponse(
                external ? "external" : "internal",
                external ? "/auth/oidc/login?domain=" + URLEncoder.encode(domain, StandardCharsets.UTF_8) : null,
                external ? idp.oidcIssuerUrl() : null,
                domain, idpType, isAnchorDomain, external, emailExists,
                null,
                emailExists ? "A user with this email address already exists." : null,
                scope.name(), scope != UserScope.ANCHOR, UserScopeDerivation.allowedClientIds(mapping)));
    }

    // ── Writes ─────────────────────────────────────────────────────────────

    /// Anchors create any scope/client; a non-anchor administrator only
    /// CLIENT-scope users in a client it can access.
    private static void create(Context ctx, State s) {
        AuthContext ac = Auth.current();
        var req = ctx.bodyAsClass(CreatePrincipalRequest.class);
        requireClientScopeForNonAnchor(ac, req.scope());
        Checks.requireUserAdmin(ac, req.clientId());
        var event = CreateUser.of(s.repo()).run(s.uow(), req.toCommand(), Auth.executionContext());
        s.repo().findById(event.userId()).ifPresent(p -> notifyNewUser(s, p, req.password()));
        ctx.status(201).json(new CreatedResponse(event.userId()));
    }

    /// The SDK create-user endpoint (spec §7): scope derived from the request
    /// + the email domain's setup, client reference resolved, partner-merge,
    /// then the shared [CreateUser]. Answers the full principal.
    private static void createUser(Context ctx, State s) {
        AuthContext ac = Auth.current();
        var req = ctx.bodyAsClass(CreateUserRequest.class);
        String email = EmailAddress.normalise(req.email());
        String domain = domainOrBadRequest(email);
        boolean isAnchorDomain = s.anchorDomains().contains(domain);
        EmailDomainMapping mapping = s.mappings().findByEmailDomain(domain).orElse(null);
        String idpType = mapping == null ? IdentityProviderType.INTERNAL.name()
                : s.identityProviders().findById(mapping.identityProviderId()).map(i -> i.type().name())
                .orElse(IdentityProviderType.INTERNAL.name());
        String requestedClient = req.clientId() == null || req.clientId().isBlank() ? null : resolveClientRef(s, req.clientId().trim());
        var derived = UserScopeDerivation.derive(req.scope(), isAnchorDomain, mapping, requestedClient);

        requireClientScopeForNonAnchor(ac, derived.scope().name());
        Checks.requireUserAdmin(ac, derived.clientId());
        ExecutionContext ec = Auth.executionContext();

        if (derived.scope() == UserScope.PARTNER) {
            Principal existing = s.repo().findByEmail(email).orElse(null);
            if (existing != null) {
                if (existing.clientId() != null && existing.clientId().equals(derived.clientId())) {
                    throw UseCaseException.conflict("EMAIL_EXISTS", "User with email '" + email + "' already exists");
                }
                GrantClientAccess.of(s.repo(), s.clients(), s.grants())
                        .run(s.uow(), new GrantClientAccessCommand(existing.id(), derived.clientId()), ec);
                ctx.json(PrincipalResponse.from(principal(s, existing.id())));
                return;
            }
        }
        var event = CreateUser.of(s.repo()).run(s.uow(),
                new CreateCommand(email, req.name(), derived.scope().name(), derived.clientId(), req.password(), idpType), ec);
        if (derived.scope() == UserScope.PARTNER && derived.clientId() != null) {
            GrantClientAccess.of(s.repo(), s.clients(), s.grants())
                    .run(s.uow(), new GrantClientAccessCommand(event.userId(), derived.clientId()), ec);
        }
        Principal created = principal(s, event.userId());
        notifyNewUser(s, created, req.password());
        ctx.json(PrincipalResponse.from(created));
    }

    /// CSV onboarding under one client (spec §7): each row its own
    /// transactions, outcomes reported per row.
    private static void bulkImport(Context ctx, State s) {
        AuthContext ac = Auth.current();
        var req = ctx.bodyAsClass(BulkImportRequest.class);
        String clientId = req.clientId() == null ? "" : req.clientId().trim();
        if (clientId.isEmpty()) throw HttpError.badRequest("CLIENT_REQUIRED", "A target client is required");
        Checks.requireUserAdmin(ac, clientId);
        if (req.users().isEmpty()) throw HttpError.badRequest("NO_ROWS", "No users to import");
        if (req.users().size() > BULK_IMPORT_MAX_ROWS) {
            throw HttpError.badRequest("TOO_MANY", "Import is limited to " + BULK_IMPORT_MAX_ROWS + " users at a time");
        }
        ExecutionContext ec = Auth.executionContext();
        var results = new ArrayList<BulkImportResult>(req.users().size());
        var seen = new HashSet<String>();
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (int i = 0; i < req.users().size(); i++) {
            BulkImportUser u = req.users().get(i);
            String email = EmailAddress.normalise(u.email());
            BulkImportResult r = importRow(s, ac, ec, clientId, i + 1, u.name() == null ? "" : u.name().trim(), email, cleanRoles(u.roles()), seen);
            switch (r.status()) {
                case "created" -> created++;
                case "exists", "dropped" -> skipped++;
                default -> failed++;
            }
            results.add(r);
        }
        ctx.json(new BulkImportResponse(created, skipped, failed, results));
    }

    private static BulkImportResult importRow(State s, AuthContext ac, ExecutionContext ec, String clientId, int row,
                                              String name, String email, List<String> roles, Set<String> seen) {
        if (email.isEmpty() || !email.contains("@")) return BulkImportResult.error(row, email, "invalid email address");
        if (name.isEmpty()) return BulkImportResult.error(row, email, "name is required");
        if (!seen.add(email)) return BulkImportResult.error(row, email, "duplicate email in file");
        if (!ac.isAnchor()) {
            try {
                assertAssignableRoles(s, roles, clientApplicationIds(s, clientId));
            } catch (UseCaseException e) {
                return BulkImportResult.error(row, email, e.error().message());
            }
        }
        if (s.repo().findByEmail(email).isPresent()) return new BulkImportResult(row, email, "exists", "already exists — skipped");
        List<String> owners = UserScopeDerivation.ownerClientIds(s.mappings().findByEmailDomain(EmailAddress.domainOf(email)).orElse(null));
        if (!owners.isEmpty() && !owners.contains(clientId)) {
            return new BulkImportResult(row, email, "dropped", "email domain is registered to another client — skipped");
        }
        String userId;
        try {
            userId = CreateUser.of(s.repo()).run(s.uow(), new CreateCommand(email, name, UserScope.CLIENT.name(), clientId, null, null), ec).userId();
        } catch (UseCaseException e) {
            return BulkImportResult.error(row, email, e.error().message());
        }
        if (!roles.isEmpty()) {
            try {
                AssignRoles.of(s.repo(), s.roles()).run(s.uow(), new AssignRolesCommand(userId, roles), ec);
            } catch (UseCaseException e) {
                return new BulkImportResult(row, email, "created", "created, but roles not applied: " + e.error().message());
            }
        }
        s.repo().findById(userId).ifPresent(p -> notifyNewUser(s, p, null));
        return new BulkImportResult(row, email, "created", null);
    }

    /// Declarative, application-less user upsert keyed on email (spec §3).
    private static void syncUsers(Context ctx, State s) {
        Checks.requireAny(Auth.current(), USER_MANAGE, USER_CREATE, USER_UPDATE, USER_DELETE, USER_ASSIGN_ROLES);
        var cmd = ctx.bodyAsClass(SyncUsersRequest.class).toCommand();
        var ev = SyncPrincipals.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.json(new SyncUsersResponse(ev.created(), ev.updated(), ev.deactivated(), ev.syncedEmails()));
    }

    private static void update(Context ctx, State s) {
        Checks.requireAny(Auth.current(), USER_CREATE, USER_UPDATE, USER_DELETE);
        String id = ctx.pathParam("id");
        UpdateUser.of(s.repo()).run(s.uow(), ctx.bodyAsClass(UpdatePrincipalRequest.class).toCommand(id), Auth.executionContext());
        ctx.json(PrincipalResponse.from(principal(s, id)));
    }

    private static void activate(Context ctx, State s) {
        Checks.requireAny(Auth.current(), USER_CREATE, USER_UPDATE, USER_DELETE);
        ActivateUser.of(s.repo()).run(s.uow(), new ActivateCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Principal activated"));
    }

    private static void deactivate(Context ctx, State s) {
        Checks.requireAny(Auth.current(), USER_CREATE, USER_UPDATE, USER_DELETE);
        DeactivateUser.of(s.repo()).run(s.uow(), new DeactivateCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Principal deactivated"));
    }

    /// The per-resource scope check lives here (not in the operation) because
    /// the operation is shared with the unauthenticated reset-confirm flow.
    private static void resetPassword(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.requireAny(ac, USER_CREATE, USER_UPDATE, USER_DELETE);
        String id = ctx.pathParam("id");
        Principal p = principal(s, id);
        Access.blockNonClientTarget(ac, p);
        Checks.checkScopeAccess(ac, p.clientId());
        ResetPassword.of(s.repo()).run(s.uow(), ctx.bodyAsClass(ResetPasswordRequest.class).toCommand(id), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Password reset successfully"));
    }

    /// Body optional: no body = plain reset email.
    private static void sendPasswordReset(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String id = ctx.pathParam("id");
        Principal p = principal(s, id);
        Checks.requireUserAdmin(ac, p.clientId());
        Access.blockNonClientTarget(ac, p);
        boolean reset2fa = !ctx.body().isBlank() && Boolean.TRUE.equals(ctx.bodyAsClass(SendPasswordResetInputBody.class).reset2fa());
        SendPasswordReset.run(s.repo(), s.passwordEmailer(), new SendPasswordResetCommand(id, reset2fa));
        ctx.json(new StatusChangeResponse("Password reset email sent"));
    }

    /// Clears a user's enrolled 2FA (anchor or a client-administrator of the
    /// user's client). Without an MFA service this answers 500 before the load,
    /// as Go does. TODO(port): the `2FA_RESET_BY_ADMIN` audit row once the
    /// audit repository gains a write path (spec §12).
    private static void resetTwoFactor(Context ctx, State s) {
        AuthContext ac = Auth.current();
        if (!s.mfa().configured()) throw UseCaseException.internal("MFA_NOT_CONFIGURED", "Two-factor service not configured", null);
        Principal p = principal(s, ctx.pathParam("id"));
        Checks.requireUserAdmin(ac, p.clientId());
        Access.blockNonClientTarget(ac, p);
        if (!p.isUser()) throw UseCaseException.validation("NOT_USER", "Two-factor reset only applies to user accounts");
        s.mfa().resetAll(p.id());
        if (p.email() != null) s.notifier().twoFactorReset(p.email());
        ctx.json(new StatusChangeResponse("Two-factor authentication reset"));
    }

    private static void delete(Context ctx, State s) {
        Checks.require(Auth.current(), USER_DELETE);
        DeleteUser.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    /// Replaces the role set. A non-anchor administrator may only name
    /// application roles its client is entitled to, and the target's other
    /// roles are preserved (spec §5.3). `added`/`removed` are set differences
    /// of the effective set against the previous one.
    private static void assignRoles(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String id = ctx.pathParam("id");
        Principal p = principal(s, id);
        List<String> requested = ctx.bodyAsClass(AssignPrincipalRolesRequest.class).roles();
        List<String> effective = requested;
        if (!ac.isAnchor()) {
            Set<String> allowed = clientApplicationIds(s, p.clientId());
            assertAssignableRoles(s, requested, allowed);
            var merged = new LinkedHashSet<>(requested);
            merged.addAll(protectedRoleNames(s, p.roleNames(), allowed));
            effective = List.copyOf(merged);
        }
        Set<String> old = new HashSet<>(p.roleNames());
        Set<String> desired = new HashSet<>(effective);
        List<String> added = desired.stream().filter(r -> !old.contains(r)).toList();
        List<String> removed = old.stream().filter(r -> !desired.contains(r)).toList();
        AssignRoles.of(s.repo(), s.roles()).run(s.uow(), new AssignRolesCommand(id, effective), Auth.executionContext());
        ctx.json(new RolesAssignedResponse(PrincipalRoleAssignmentDTO.listFor(principal(s, id)), added, removed));
    }

    /// Adds one role (no-op when already held).
    private static void addRole(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String id = ctx.pathParam("id");
        Principal p = principal(s, id);
        String role = ctx.bodyAsClass(AddRoleRequest.class).role();
        if (!ac.isAnchor()) assertAssignableRoles(s, List.of(role), clientApplicationIds(s, p.clientId()));
        if (!p.hasRole(role)) {
            var desired = new ArrayList<>(p.roleNames());
            desired.add(role);
            AssignRoles.of(s.repo(), s.roles()).run(s.uow(), new AssignRolesCommand(id, desired), Auth.executionContext());
            p = principal(s, id);
        }
        ctx.json(PrincipalResponse.from(p));
    }

    /// Removes one role (no-op when not held). A non-anchor may only remove
    /// roles it could also assign, so it cannot strip platform / other-app roles.
    private static void removeRole(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String id = ctx.pathParam("id");
        Principal p = principal(s, id);
        String role = ctx.pathParam("role");
        if (!ac.isAnchor()) assertAssignableRoles(s, List.of(role), clientApplicationIds(s, p.clientId()));
        if (p.hasRole(role)) {
            List<String> desired = p.roleNames().stream().filter(r -> !r.equals(role)).toList();
            AssignRoles.of(s.repo(), s.roles()).run(s.uow(), new AssignRolesCommand(id, desired), Auth.executionContext());
            p = principal(s, id);
        }
        ctx.json(PrincipalResponse.from(p));
    }

    /// Replaces the explicit application set; a non-anchor administrator is
    /// bounded to its client's applications and the target's other grants
    /// are preserved; all-applications may only be granted by a caller that
    /// holds it (spec §5.3).
    private static void assignApplicationAccess(Context ctx, State s) {
        AuthContext ac = Auth.current();
        String id = ctx.pathParam("id");
        Principal p = principal(s, id);
        var req = ctx.bodyAsClass(AssignApplicationAccessRequest.class);
        if (Boolean.TRUE.equals(req.allApplications()) && !ac.allApplications()) {
            throw HttpError.forbidden("Only an all-applications administrator may grant all-applications access");
        }
        List<String> desired = req.applicationIds();
        if (!ac.isAnchor()) {
            Set<String> allowed = clientApplicationIds(s, p.clientId());
            for (String appId : desired) {
                if (!allowed.contains(appId)) {
                    throw UseCaseException.authorization("APP_FORBIDDEN", "application the client cannot access: " + appId);
                }
            }
            var merged = new LinkedHashSet<>(desired);
            p.accessibleApplicationIds().stream().filter(a -> !allowed.contains(a)).forEach(merged::add);
            desired = List.copyOf(merged);
        }
        Set<String> old = new HashSet<>(p.accessibleApplicationIds());
        Set<String> next = new HashSet<>(desired);
        int added = (int) next.stream().filter(a -> !old.contains(a)).count();
        int removed = (int) old.stream().filter(a -> !next.contains(a)).count();
        AssignApplicationAccess.of(s.repo(), s.applications())
                .run(s.uow(), new AssignApplicationAccessCommand(id, desired, req.allApplications()), Auth.executionContext());
        boolean effectiveAll = req.allApplications() == null ? p.allApplications() : req.allApplications();
        ctx.json(new SetApplicationAccessResponse(resolveApplications(s, desired), added, removed, effectiveAll));
    }

    private static void grantClientAccess(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        String id = ctx.pathParam("id");
        String clientId = ctx.bodyAsClass(GrantClientAccessRequest.class).clientId();
        GrantClientAccess.of(s.repo(), s.clients(), s.grants()).run(s.uow(), new GrantClientAccessCommand(id, clientId), Auth.executionContext());
        ctx.json(ClientAccessGrantResponse.from(s.grants().findByPrincipalAndClient(id, clientId)
                .orElseThrow(() -> UseCaseException.internal("REPO", "grant not found after create", null))));
    }

    private static void revokeClientAccess(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        RevokeClientAccess.of(s.repo(), s.grants())
                .run(s.uow(), new RevokeClientAccessCommand(ctx.pathParam("id"), ctx.pathParam("clientId")), Auth.executionContext());
        ctx.status(204);
    }

    private static void setClientAssociation(Context ctx, State s) {
        Checks.requireAnchor(Auth.current());
        String id = ctx.pathParam("id");
        SetClientAssociation.of(s.repo(), s.clients())
                .run(s.uow(), ctx.bodyAsClass(ClientAssociationRequest.class).toCommand(id), Auth.executionContext());
        ctx.json(PrincipalResponse.from(principal(s, id)));
    }

    /// No coarse gate: the operation's self-or-user-admin rule is the whole check.
    ///
    /// The plaintext comes back through a **local** sink rather than a
    /// process-wide stash: it reaches the response exactly once, cannot
    /// outlive this frame, and is only read after `run` returns — so a
    /// rolled-back commit discloses nothing. See [DeveloperSecrets].
    private static void setDeveloperCredential(Context ctx, State s) {
        var plaintext = new java.util.concurrent.atomic.AtomicReference<String>();
        var ev = SetDeveloperCredential.of(s.repo(), s.developerSecrets(), plaintext::set)
                .run(s.uow(), new SetDeveloperCredentialCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.json(new SetDeveloperCredentialResponse(ev.userId(), plaintext.get()));
    }

    private static void revokeDeveloperCredential(Context ctx, State s) {
        RevokeDeveloperCredential.of(s.repo())
                .run(s.uow(), new RevokeDeveloperCredentialCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side and command-shaping helpers ──────────────────────────────

    /// Load-or-404 for the handlers (`Principal_NOT_FOUND`).
    private static Principal principal(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("Principal", id));
    }

    private static boolean isSelf(AuthContext ac, String id) {
        return ac != null && ac.principalId().equals(id);
    }

    /// A non-anchor administrator may only create CLIENT-scope users.
    private static void requireClientScopeForNonAnchor(AuthContext ac, String scope) {
        if (ac == null) throw HttpError.unauthenticated();
        if (!ac.isAnchor() && !UserScope.CLIENT.name().equals(scope)) {
            throw HttpError.forbidden("Client administrators can only create client-scope users");
        }
    }

    /// The domain of a normalised email, or 400 `INVALID_EMAIL`.
    private static String domainOrBadRequest(String email) {
        String domain = EmailAddress.domainOf(email);
        if (domain == null) throw HttpError.badRequest("INVALID_EMAIL", "Invalid email format");
        return domain;
    }

    /// A `clt_` id or the client's identifier slug → the client id; unknown
    /// references fail closed (a typo must not create a mis-scoped user).
    private static String resolveClientRef(State s, String ref) {
        return s.clients().findById(ref)
                .or(() -> s.clients().findByIdentifier(ref.toLowerCase(Locale.ROOT)))
                .map(Client::id)
                .orElseThrow(() -> HttpError.notFound("Client", ref));
    }

    /// The application ids a client is entitled to (enabled client-configs) —
    /// the bound a non-anchor administrator is held to; empty for a clientless target.
    private static Set<String> clientApplicationIds(State s, String clientId) {
        if (clientId == null || clientId.isEmpty()) return Set.of();
        return s.clientConfigs().findByClient(clientId).stream()
                .filter(ClientConfig::enabled).map(ClientConfig::applicationId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /// Every role a non-anchor names must exist, be application-scoped, and
    /// belong to an allowed application (spec §5.3).
    private static void assertAssignableRoles(State s, List<String> roleNames, Set<String> allowed) {
        for (String name : roleNames) {
            Role r = s.roles().findByName(name)
                    .orElseThrow(() -> UseCaseException.validation("UNKNOWN_ROLE", "role not found: " + name));
            if (r.applicationId() == null) {
                throw UseCaseException.authorization("PLATFORM_ROLE_FORBIDDEN", "client administrators cannot assign platform roles");
            }
            if (!allowed.contains(r.applicationId())) {
                throw UseCaseException.authorization("ROLE_APP_FORBIDDEN", "role belongs to an application the client cannot access");
            }
        }
    }

    /// The target's existing roles a non-anchor may not manage — platform,
    /// unknown, or outside the allowed applications — which its SET preserves.
    private static List<String> protectedRoleNames(State s, List<String> roleNames, Set<String> allowed) {
        return roleNames.stream()
                .filter(name -> s.roles().findByName(name).map(r -> r.applicationId() == null || !allowed.contains(r.applicationId())).orElse(true))
                .toList();
    }

    /// Application ids → `{id, code, name}` rows; ids that no longer resolve are skipped.
    private static List<ApplicationAccessResponse> resolveApplications(State s, List<String> ids) {
        return ids.stream().map(id -> s.applications().findById(id))
                .flatMap(Optional::stream)
                .map(a -> new ApplicationAccessResponse(a.id(), a.code(), a.name()))
                .toList();
    }

    /// Trims, drops blanks and de-duplicates the role names of a CSV cell.
    private static List<String> cleanRoles(List<String> roles) {
        if (roles == null) return List.of();
        var out = new LinkedHashSet<String>();
        for (String r : roles) {
            if (r != null && !r.isBlank()) out.add(r.trim());
        }
        return List.copyOf(out);
    }

    /// Best-effort onboarding mail (spec §7): nothing for service accounts,
    /// federated users or blank emails; a passwordless account gets the
    /// "set your password" invite, one created with a password the welcome.
    private static void notifyNewUser(State s, Principal p, String password) {
        if (p.userIdentity() == null || p.isFederated()) return;
        String email = p.email() == null ? "" : p.email().trim();
        if (email.isEmpty()) return;
        try {
            if (password == null || password.isEmpty()) {
                s.inviteEmailer().sendInvite(p);
            } else {
                s.notifier().accountCreated(email);
            }
        } catch (RuntimeException e) {
            LOG.warn("new-user notification failed for principal {}", p.id(), e);
        }
    }

    /// The list query (spec §3): every parameter optional, matched in memory.
    private record ListQuery(String type, String clientId, String active, String q, List<String> roles, int page, int pageSize,
                             String sortField, boolean descending) {

        static ListQuery from(Context ctx) {
            return new ListQuery(
                    upper(ctx.queryParam("type")),
                    trimmed(ctx.queryParam("clientId")),
                    trimmed(ctx.queryParam("active")),
                    trimmed(ctx.queryParam("q")).toLowerCase(Locale.ROOT),
                    csv(ctx.queryParam("roles")),
                    intParam(ctx, "page"),
                    intParam(ctx, "pageSize"),
                    trimmed(ctx.queryParam("sortField")),
                    "desc".equalsIgnoreCase(trimmed(ctx.queryParam("sortOrder"))));
        }

        boolean matches(Principal p) {
            if (!type.isEmpty() && !p.type().name().equals(type)) return false;
            if (active.equals("true") && !p.active()) return false;
            if (active.equals("false") && p.active()) return false;
            if (!clientId.isEmpty() && !p.reachesClient(clientId)) return false;
            if (!q.isEmpty() && !(p.name().toLowerCase(Locale.ROOT).contains(q)
                    || (p.email() != null && p.email().toLowerCase(Locale.ROOT).contains(q)))) return false;
            return roles.isEmpty() || roles.stream().anyMatch(p::hasRole);
        }

        /// `name` / `email` case-insensitively, otherwise `createdAt`; stable, reversed for `desc`.
        Comparator<Principal> order() {
            Comparator<Principal> c = switch (sortField) {
                case "name" -> Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT));
                case "email" -> Comparator.comparing(p -> p.email() == null ? "" : p.email().toLowerCase(Locale.ROOT));
                default -> Comparator.comparing(Principal::createdAt);
            };
            return descending ? c.reversed() : c;
        }

        /// 0-based page; `pageSize <= 0` returns everything.
        List<Principal> page(List<Principal> all) {
            if (pageSize <= 0) return all;
            int start = Math.min(Math.max(page, 0) * pageSize, all.size());
            return all.subList(start, Math.min(start + pageSize, all.size()));
        }

        private static String trimmed(String v) {
            return v == null ? "" : v.trim();
        }

        private static String upper(String v) {
            return trimmed(v).toUpperCase(Locale.ROOT);
        }

        private static List<String> csv(String v) {
            if (v == null) return List.of();
            var out = new ArrayList<String>();
            for (String part : v.split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
            return List.copyOf(out);
        }

        /// Absent or unparsable → 0 (Go's integer query binding default).
        private static int intParam(Context ctx, String name) {
            String v = ctx.queryParam(name);
            if (v == null || v.isBlank()) return 0;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw HttpError.badRequest("VALIDATION", "query parameter '" + name + "' must be an integer");
            }
        }
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// Body of `POST /api/principals`.
    public record CreatePrincipalRequest(String email, String name, String scope, String clientId, String password, String idpType) {
        public CreateCommand toCommand() {
            return new CreateCommand(email, name, scope, clientId, password, idpType);
        }

        @Override
        public String toString() {
            return "CreatePrincipalRequest[email=" + email + ", scope=" + scope + ", clientId=" + clientId + ", password=***]";
        }
    }

    /// Body of `POST /api/principals/users`; `enforcePasswordComplexity` is accepted and ignored (spec §3).
    public record CreateUserRequest(String email, String name, String password, String scope, String clientId,
                                    Boolean enforcePasswordComplexity) {
        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + ", scope=" + scope + ", clientId=" + clientId + ", password=***]";
        }
    }

    /// Body of `PUT /api/principals/{id}`; the path id is authoritative.
    public record UpdatePrincipalRequest(String name, Boolean active, String email) {
        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, active, email);
        }
    }

    /// Body of `POST /api/principals/{id}/reset-password`.
    public record ResetPasswordRequest(String newPassword, Boolean enforcePasswordComplexity) {
        public ResetPasswordCommand toCommand(String id) {
            return new ResetPasswordCommand(id, newPassword, enforcePasswordComplexity);
        }

        @Override
        public String toString() {
            return "ResetPasswordRequest[newPassword=***, enforcePasswordComplexity=" + enforcePasswordComplexity + "]";
        }
    }

    /// Optional body of `POST /api/principals/{id}/send-password-reset`.
    public record SendPasswordResetInputBody(Boolean reset2fa) {
    }

    /// Body of `PUT /api/principals/{id}/roles`.
    public record AssignPrincipalRolesRequest(List<String> roles) {
        public AssignPrincipalRolesRequest {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /// Body of `POST /api/principals/{id}/roles`.
    public record AddRoleRequest(String role) {
    }

    /// Body of `PUT /api/principals/{id}/application-access`; `allApplications` absent = unchanged.
    public record AssignApplicationAccessRequest(List<String> applicationIds, Boolean allApplications) {
        public AssignApplicationAccessRequest {
            applicationIds = applicationIds == null ? List.of() : List.copyOf(applicationIds);
        }
    }

    /// Body of `POST /api/principals/{id}/client-access`.
    public record GrantClientAccessRequest(String clientId) {
    }

    /// Body of `PUT /api/principals/{id}/client-association`: `clientId` or `*`; `mode` for a specific client.
    public record ClientAssociationRequest(String clientId, String mode) {
        public SetClientAssociationCommand toCommand(String id) {
            return new SetClientAssociationCommand(id, clientId, ClientAssociationMode.parse(mode));
        }
    }

    /// Body of `POST /api/principals/bulk-import`.
    public record BulkImportRequest(String clientId, List<BulkImportUser> users) {
        public BulkImportRequest {
            users = users == null ? List.of() : List.copyOf(users);
        }
    }

    /// One CSV row.
    public record BulkImportUser(String name, String email, List<String> roles) {
        public BulkImportUser {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /// Body of `POST /api/principals/sync`.
    public record SyncUsersRequest(List<SyncUserInput> principals) {
        public SyncUsersRequest {
            principals = principals == null ? List.of() : List.copyOf(principals);
        }

        public SyncPrincipalsCommand toCommand() {
            return new SyncPrincipalsCommand(null, principals.stream().map(SyncUserInput::toInput).toList(), false);
        }
    }

    /// One entry of the user-sync body; `active` defaults to `true`.
    public record SyncUserInput(String email, String name, List<String> roles, Boolean active, String passwordHash) {
        public SyncUserInput {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        SyncPrincipalInput toInput() {
            return new SyncPrincipalInput(email, name, roles, active == null || active, passwordHash);
        }

        @Override
        public String toString() {
            return "SyncUserInput[email=" + email + ", name=" + name + ", roles=" + roles + ", active=" + active + ", passwordHash=***]";
        }
    }

    /// The wire shape of a principal (spec §3): flat, roles as names, never a
    /// hash or secret. `twoFactorMethods` only on the by-id read with an MFA service.
    public record PrincipalResponse(
            String id,
            String type,
            String scope,
            String clientId,
            String name,
            boolean active,
            String email,
            String idpType,
            List<String> roles,
            boolean isAnchorUser,
            List<String> grantedClientIds,
            Instant createdAt,
            Instant updatedAt,
            boolean hasDeveloperCredential,
            Instant developerCredentialUpdatedAt,
            List<String> twoFactorMethods) {

        public static PrincipalResponse from(Principal p) {
            return from(p, null);
        }

        public static PrincipalResponse from(Principal p, List<String> twoFactorMethods) {
            var u = p.userIdentity();
            return new PrincipalResponse(p.id(), p.type().name(), p.scope().name(), p.clientId(), p.name(), p.active(),
                    u == null ? null : u.email(),
                    u == null ? null : u.providerOrInternal(),
                    p.roleNames(), p.scope().isAnchor(), p.assignedClients(), p.createdAt(), p.updatedAt(),
                    p.hasDeveloperSecret(),
                    u == null || !u.hasDeveloperSecret() ? null : u.devClientSecretUpdatedAt(),
                    twoFactorMethods);
        }
    }

    /// `{principals, total}` — `total` is the filtered count before pagination.
    public record PrincipalListResponse(List<PrincipalResponse> principals, int total) {
    }

    public record DeveloperUserListResponse(List<PrincipalResponse> principals, int total) {
    }

    public record PrincipalVersionResponse(Instant updatedAt) {
    }

    /// One role assignment row; `id` is synthetic (`{principalId}-role-{index}`) for a stable UI key.
    public record PrincipalRoleAssignmentDTO(String id, String roleName, String assignmentSource, Instant assignedAt) {
        static List<PrincipalRoleAssignmentDTO> listFor(Principal p) {
            var out = new ArrayList<PrincipalRoleAssignmentDTO>(p.roles().size());
            int i = 0;
            for (RoleAssignment ra : p.roles()) {
                out.add(new PrincipalRoleAssignmentDTO(p.id() + "-role-" + i++, ra.role(),
                        ra.assignmentSource() == null ? "ADMIN" : ra.assignmentSource(), ra.assignedAt()));
            }
            return List.copyOf(out);
        }
    }

    public record PrincipalRoleListResponse(List<PrincipalRoleAssignmentDTO> roles) {
    }

    public record RolesAssignedResponse(List<PrincipalRoleAssignmentDTO> roles, List<String> added, List<String> removed) {
    }

    public record ApplicationAccessResponse(String applicationId, String applicationCode, String applicationName) {
    }

    public record ApplicationAccessListResponse(List<ApplicationAccessResponse> applications, int total, boolean allApplications) {
    }

    public record SetApplicationAccessResponse(List<ApplicationAccessResponse> applications, int added, int removed, boolean allApplications) {
    }

    public record PrincipalAvailableApplication(String id, String code, String name) {
    }

    public record PrincipalAvailableApplicationsResponse(List<PrincipalAvailableApplication> applications) {
    }

    /// One client-access grant; `expiresAt` is never set today.
    public record ClientAccessGrantResponse(String id, String clientId, Instant grantedAt, Instant expiresAt) {
        static ClientAccessGrantResponse from(ClientAccessGrant g) {
            return new ClientAccessGrantResponse(g.id(), g.clientId(), g.grantedAt(), null);
        }
    }

    public record ClientAccessGrantListResponse(List<ClientAccessGrantResponse> grants) {
    }

    /// `GET /api/principals/check-email-domain`: the slim auth fields plus the
    /// rich create-form hints; `info` / `warning` serialise as `null` when absent.
    public record CheckEmailDomainResponse(
            String authMethod,
            String loginUrl,
            String idpIssuer,
            String domain,
            String authProvider,
            boolean isAnchorDomain,
            boolean hasIdpConfig,
            boolean emailExists,
            @JsonInclude(JsonInclude.Include.ALWAYS) String info,
            @JsonInclude(JsonInclude.Include.ALWAYS) String warning,
            String derivedScope,
            boolean requiresClientId,
            List<String> allowedClientIds) {
    }

    /// `POST /api/principals/{id}/developer-credential`: the plaintext is returned once, here.
    public record SetDeveloperCredentialResponse(String id, String clientSecret) {
        @Override
        public String toString() {
            return "SetDeveloperCredentialResponse[id=" + id + ", clientSecret=" + (clientSecret == null ? "null" : "***") + "]";
        }
    }

    public record BulkImportResult(int row, String email, String status, String message) {
        static BulkImportResult error(int row, String email, String message) {
            return new BulkImportResult(row, email, "error", message);
        }
    }

    public record BulkImportResponse(int created, int skipped, int failed, List<BulkImportResult> results) {
    }

    public record SyncUsersResponse(int created, int updated, int deleted, List<String> syncedEmails) {
    }
}

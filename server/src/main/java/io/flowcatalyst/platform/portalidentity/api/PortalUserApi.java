package io.flowcatalyst.platform.portalidentity.api;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalInviteEmailer;
import io.flowcatalyst.platform.portalidentity.operations.DeleteCommand;
import io.flowcatalyst.platform.portalidentity.operations.DeletePortalIdentity;
import io.flowcatalyst.platform.portalidentity.operations.EnsureCommand;
import io.flowcatalyst.platform.portalidentity.operations.EnsurePortalIdentity;
import io.flowcatalyst.platform.portalidentity.operations.SetStatusCommand;
import io.flowcatalyst.platform.portalidentity.operations.SetPortalIdentityStatus;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/// The `/api/portal-users` surface (spec `auth-identity.md` §5.7; lockfile
/// shapes). Inside the authenticator; authorization is `Checks.requirePortalUserView`
/// for the read, `Checks.requirePortalUserManage` for every write.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/portal-users` | 200 [PortalUserListResponse] |
/// | POST | `/api/portal-users` | 200 [PortalUserResponse] |
/// | POST | `/api/portal-users/{id}/activate` | 200 [StatusChangeResponse] |
/// | POST | `/api/portal-users/{id}/deactivate` | 200 [StatusChangeResponse] |
/// | DELETE | `/api/portal-users/{id}` | 200 [StatusChangeResponse] |
public final class PortalUserApi {

    private PortalUserApi() {
    }

    public record State(PortalIdentityRepository repo, ClientRepository clients, OAuthClientRepository oauthClients,
                        IdentityProviderRepository identityProviders, UnitOfWork uow, PortalInviteEmailer emailer) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(identityProviders, "identityProviders");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(emailer, "emailer");
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/portal-users", Auth.scoped(ctx -> list(ctx, s)));
        routes.post("/api/portal-users", Auth.scoped(ctx -> ensure(ctx, s)));
        routes.post("/api/portal-users/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
        routes.post("/api/portal-users/{id}/deactivate", Auth.scoped(ctx -> deactivate(ctx, s)));
        routes.delete("/api/portal-users/{id}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        String clientId = ctx.queryParam("clientId");
        if (clientId == null || clientId.isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId query param is required");
        }
        Checks.requirePortalUserView(Auth.current(), clientId);
        List<PortalUserListItem> items = s.repo().findByClient(clientId).stream().map(PortalUserListItem::from).toList();
        ctx.json(new PortalUserListResponse(items));
    }

    private static void ensure(Context ctx, State s) {
        var req = ctx.bodyAsClass(PortalUserRequest.class);
        if (req.clientId() == null || req.clientId().isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId is required");
        }
        Checks.requirePortalUserManage(Auth.current(), req.clientId());

        List<String> portalRedirectUris = portalRedirectUrisFor(s, req.clientId());
        String requestedRedirect = req.redirectUri() == null ? null : req.redirectUri().trim();
        String target;
        if (requestedRedirect != null && !requestedRedirect.isBlank()) {
            if (!portalRedirectUris.contains(requestedRedirect)) {
                throw UseCaseException.validation("REDIRECT_URI_INVALID",
                        "redirectUri must exactly match a registered redirect URI of one of the client's portal OAuth clients");
            }
            target = requestedRedirect;
        } else {
            target = defaultPortalRedirect(portalRedirectUris);
        }

        var cmd = new EnsureCommand(req.clientId(), req.email(), req.name(), "INVITE");
        var event = EnsurePortalIdentity.of(s.repo(), s.clients()).run(s.uow(), cmd, Auth.executionContext());
        PortalIdentity identity = s.repo().findById(event.identityId())
                .orElseThrow(() -> HttpError.internal("REPO", "portal identity ensured but row not found", null));

        String domain = domainOf(identity.email());
        boolean ssoManaged = domain != null && ssoIdpFor(s, domain).isPresent();

        if (ssoManaged) {
            boolean invited = false;
            String inviteUrl = null;
            if (Boolean.TRUE.equals(req.returnInviteLink())) {
                inviteUrl = target;
            } else if (target != null) {
                s.emailer().sendPortalSsoInvite(identity.email(), target);
                invited = true;
            }
            ctx.json(new PortalUserResponse(identity.id(), event.created(), invited, inviteUrl, true, identity.canSignInWithPassword()));
            return;
        }

        boolean invited = false;
        String inviteUrl = null;
        if (!identity.canSignInWithPassword()) {
            if (Boolean.TRUE.equals(req.returnInviteLink())) {
                try {
                    inviteUrl = s.emailer().inviteLink(identity, target);
                } catch (RuntimeException e) {
                    throw UseCaseException.internal("INVITE_LINK", "could not build the invite link", e);
                }
            } else {
                try {
                    s.emailer().sendPortalInvite(identity, target);
                    invited = true;
                } catch (RuntimeException e) {
                    throw UseCaseException.internal("INVITE_EMAIL", "could not send the invite email", e);
                }
            }
        }
        ctx.json(new PortalUserResponse(identity.id(), event.created(), invited, inviteUrl, null, identity.canSignInWithPassword()));
    }

    private static void activate(Context ctx, State s) {
        setStatus(ctx, s, "ACTIVE", "Portal user activated");
    }

    private static void deactivate(Context ctx, State s) {
        setStatus(ctx, s, "DISABLED", "Portal user deactivated");
    }

    private static void setStatus(Context ctx, State s, String status, String message) {
        var body = ctx.bodyAsClass(PortalUserClientBody.class);
        if (body.clientId() == null || body.clientId().isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId is required");
        }
        Checks.requirePortalUserManage(Auth.current(), body.clientId());
        String id = ctx.pathParam("id");
        SetPortalIdentityStatus.of(s.repo())
                .run(s.uow(), new SetStatusCommand(id, body.clientId(), null, status), Auth.executionContext());
        ctx.json(new StatusChangeResponse(message));
    }

    private static void delete(Context ctx, State s) {
        String id = ctx.pathParam("id");
        String clientId = ctx.queryParam("clientId");
        // No CLIENT_ID_REQUIRED here (spec §5.7: `Delete` only validates `ID_REQUIRED`) —
        // Checks already fails closed for a non-anchor with a blank/absent clientId,
        // and an anchor is entitled to delete by id alone.
        Checks.requirePortalUserManage(Auth.current(), clientId);
        DeletePortalIdentity.of(s.repo()).run(s.uow(), new DeleteCommand(clientId, id), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Portal user deleted"));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// The registered redirect URIs of every OAuth client flagged as this
    /// tenant's portal entry point (spec §5.7: `OAuthClientRepository.findAll`
    /// filtered by `portalClientId()`).
    private static List<String> portalRedirectUrisFor(State s, String clientId) {
        return s.oauthClients().findAll().stream()
                .filter(c -> clientId.equals(c.portalClientId()))
                .flatMap(c -> c.redirectUris().stream())
                .toList();
    }

    /// The first parseable, non-wildcard registered URI's `scheme://host/`,
    /// else `null` (spec §5.7).
    private static String defaultPortalRedirect(List<String> registeredUris) {
        for (String uri : registeredUris) {
            String origin = originOf(uri);
            if (origin != null) {
                return origin;
            }
        }
        return null;
    }

    private static String originOf(String uri) {
        if (uri == null || uri.indexOf('*') >= 0) {
            return null;
        }
        try {
            URI parsed = new URI(uri);
            String host = parsed.getHost();
            if (parsed.getScheme() == null || host == null || host.isEmpty()) {
                return null;
            }
            String authority = parsed.getPort() == -1 ? host : host + ":" + parsed.getPort();
            return parsed.getScheme() + "://" + authority + "/";
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String domainOf(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return null;
        }
        return email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /// An OIDC identity provider whose derived `allowedEmailDomains` names
    /// `domain`, case-insensitively (spec §5.7).
    private static Optional<IdentityProvider> ssoIdpFor(State s, String domain) {
        return s.identityProviders().findAll().stream()
                .filter(idp -> idp.type() == IdentityProviderType.OIDC)
                .filter(idp -> idp.allowedEmailDomains().stream().anyMatch(d -> d.equalsIgnoreCase(domain)))
                .findFirst();
    }

    // ── Wire DTOs (lockfile shapes) ──────────────────────────────────────────

    public record PortalUserRequest(String clientId, String email, String name, Boolean returnInviteLink, String redirectUri) {
    }

    public record PortalUserClientBody(String clientId) {
    }

    public record PortalUserResponse(
            String identityId, boolean created, boolean invited, String inviteUrl, Boolean ssoManaged, boolean hasPassword) {
    }

    public record PortalUserListItem(
            String identityId, String email, String name, String status, String source, boolean hasPassword,
            Instant lastLoginAt, Instant createdAt, Instant updatedAt) {

        static PortalUserListItem from(PortalIdentity pi) {
            return new PortalUserListItem(pi.id(), pi.email(), pi.name() == null ? "" : pi.name(), pi.status().name(), pi.source().name(), // name is required on the wire; Go writes ""
                    pi.canSignInWithPassword(), pi.lastLoginAt(), pi.createdAt(), pi.updatedAt());
        }
    }

    public record PortalUserListResponse(List<PortalUserListItem> portalUsers) {
        public PortalUserListResponse {
            portalUsers = portalUsers == null ? List.of() : List.copyOf(portalUsers);
        }
    }

    public record StatusChangeResponse(String message) {
    }
}

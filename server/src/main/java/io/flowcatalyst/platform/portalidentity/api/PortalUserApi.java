package io.flowcatalyst.platform.portalidentity.api;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppCode;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrant;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalInviteEmailer;
import io.flowcatalyst.platform.portalidentity.operations.DeleteCommand;
import io.flowcatalyst.platform.portalidentity.operations.DeletePortalIdentity;
import io.flowcatalyst.platform.portalidentity.operations.EnsureCommand;
import io.flowcatalyst.platform.portalidentity.operations.EnsurePortalIdentity;
import io.flowcatalyst.platform.portalidentity.operations.GrantPortalIdentityApp;
import io.flowcatalyst.platform.portalidentity.operations.GrantPortalIdentityAppCommand;
import io.flowcatalyst.platform.portalidentity.operations.RevokePortalIdentityApp;
import io.flowcatalyst.platform.portalidentity.operations.RevokePortalIdentityAppCommand;
import io.flowcatalyst.platform.portalidentity.operations.SetStatusCommand;
import io.flowcatalyst.platform.portalidentity.operations.SetPortalIdentityStatus;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.util.stream.Collectors.toSet;

/// The `/api/portal-users` surface (spec `auth-identity.md` §5.7;
/// `portal-apps.md` §4.1-§4.3; lockfile shapes). Inside the authenticator;
/// authorization is `Checks.requirePortalUserView` for the read,
/// `Checks.requirePortalUserManage` for every write.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/portal-users` | 200 [PortalUserListResponse] |
/// | POST | `/api/portal-users` | 200 [PortalUserResponse] |
/// | POST | `/api/portal-users/{id}/activate` | 200 [StatusChangeResponse] |
/// | POST | `/api/portal-users/{id}/deactivate` | 200 [StatusChangeResponse] |
/// | DELETE | `/api/portal-users/{id}` | 200 [StatusChangeResponse] |
/// | POST | `/api/portal-users/{id}/apps` | 200 [StatusChangeResponse] |
/// | DELETE | `/api/portal-users/{id}/apps/{portalAppCode}` | 200 [StatusChangeResponse] |
public final class PortalUserApi {

    private PortalUserApi() {
    }

    public record State(PortalIdentityRepository repo, ClientRepository clients, OAuthClientRepository oauthClients,
                        IdentityProviderRepository identityProviders, PortalAppRepository portalApps, UnitOfWork uow,
                        PortalInviteEmailer emailer) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(identityProviders, "identityProviders");
            Objects.requireNonNull(portalApps, "portalApps");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(emailer, "emailer");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/portal-users", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/portal-users", Auth.scoped(ctx -> ensure(ctx, s)));
        write.post("/api/portal-users/{id}/activate", Auth.scoped(ctx -> activate(ctx, s)));
        write.post("/api/portal-users/{id}/deactivate", Auth.scoped(ctx -> deactivate(ctx, s)));
        write.delete("/api/portal-users/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        write.post("/api/portal-users/{id}/apps", Auth.scoped(ctx -> grantApp(ctx, s)));
        write.delete("/api/portal-users/{id}/apps/{portalAppCode}", Auth.scoped(ctx -> revokeApp(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    /// §4.2: search over `PortalIdentityRepository.search`. `page` (negative
    /// ⇒ 0) and `size` (default 100, ≤0 ⇒ 100, cap 1000) are clamped here —
    /// the repository trusts the values it is given. `unassigned=true`
    /// combined with a non-blank `portalAppCode` is `FILTER_CONFLICT`,
    /// checked before the app code is resolved (spec §4.2).
    private static void list(Exchange ctx, State s) {
        String clientId = ctx.queryParam("clientId");
        if (clientId == null || clientId.isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId query param is required");
        }
        Checks.requirePortalUserView(Auth.current(), clientId);

        boolean unassigned = Boolean.parseBoolean(ctx.queryParam("unassigned"));
        String rawAppCode = ctx.queryParam("portalAppCode");
        boolean hasAppCode = rawAppCode != null && !rawAppCode.isBlank();
        if (unassigned && hasAppCode) {
            throw UseCaseException.validation("FILTER_CONFLICT", "unassigned and portalAppCode cannot be combined");
        }

        String portalAppId = null;
        if (hasAppCode) {
            portalAppId = resolveApp(s, clientId, rawAppCode).id();
        }

        int page = Math.max(intParam(ctx, "page"), 0);
        int rawSize = intParam(ctx, "size");
        int size = rawSize <= 0 ? 100 : Math.min(rawSize, 1000);

        var found = s.repo().search(new PortalIdentityRepository.SearchFilter(clientId, ctx.queryParam("q"), portalAppId, unassigned, page, size));

        var appIds = found.items().stream().flatMap(pi -> pi.apps().stream()).map(PortalAppGrant::appId).collect(toSet());
        Map<String, PortalApp> apps = s.portalApps().findByIds(appIds);

        Instant now = Instant.now();
        List<PortalUserListItem> items = found.items().stream().map(pi -> PortalUserListItem.from(pi, apps, now)).toList();
        ctx.json(new PortalUserListResponse(items, found.total(), page, size));
    }

    /// §4.1, all six steps.
    private static void ensure(Exchange ctx, State s) {
        var req = ctx.bodyAsClass(PortalUserRequest.class);
        if (req.clientId() == null || req.clientId().isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId is required");
        }
        Checks.requirePortalUserManage(Auth.current(), req.clientId());

        // Step 1: resolve portalAppCode (if given) to the client's app.
        PortalApp app = null;
        String normalizedAppCode = null;
        if (req.portalAppCode() != null && !req.portalAppCode().isBlank()) {
            normalizedAppCode = PortalAppCode.normalize(req.portalAppCode());
            app = resolveApp(s, req.clientId(), req.portalAppCode());
        }

        // Step 2: the redirect target — an exact match of a registered URI, or the
        // default origin, ordered with the resolved app's own OAuth clients first.
        List<String> portalRedirectUris = portalRedirectUrisFor(s, req.clientId(), app == null ? null : app.id());
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

        // Step 3: Ensure (§3.1), source INVITE, granting the app if one was resolved.
        var cmd = new EnsureCommand(req.clientId(), req.email(), req.name(), "INVITE", app == null ? null : app.id());
        var event = EnsurePortalIdentity.of(s.repo(), s.clients(), s.portalApps()).run(s.uow(), cmd, Auth.executionContext());
        PortalIdentity identity = s.repo().findById(event.identityId())
                .orElseThrow(() -> HttpError.internal("REPO", "portal identity ensured but row not found", null));

        Instant now = Instant.now();
        String domain = domainOf(identity.email());
        boolean ssoManaged = domain != null && ssoIdpFor(s, domain).isPresent();

        boolean invited = false;
        String inviteUrl = null;

        if (ssoManaged) {
            // Step 4: the SSO branch. "pending" = never signed in.
            boolean pending = identity.lastLoginAt() == null;
            if (Boolean.TRUE.equals(req.returnInviteLink())) {
                inviteUrl = target;
            } else if (target != null && pending) {
                s.emailer().sendPortalSsoInvite(identity.email(), target);
                invited = true;
            }
            if (pending && (invited || inviteUrl != null)) {
                s.repo().markInvited(identity.id(), now, null); // an SSO invite never expires
            }
        } else if (!identity.canSignInWithPassword()) {
            // Step 5: the password branch — only when the identity has no password yet.
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
            s.repo().markInvited(identity.id(), now, s.emailer().inviteExpiresAt(now));
        }

        // Step 6: state is evaluated AFTER the marking above — re-read the identity.
        PortalIdentity reloaded = s.repo().findById(identity.id()).orElseThrow();
        ctx.json(new PortalUserResponse(identity.id(), event.created(), invited, inviteUrl,
                ssoManaged ? Boolean.TRUE : null, reloaded.canSignInWithPassword(), normalizedAppCode,
                reloaded.state(now).name()));
    }

    private static void activate(Exchange ctx, State s) {
        setStatus(ctx, s, "ACTIVE", "Portal user activated");
    }

    private static void deactivate(Exchange ctx, State s) {
        setStatus(ctx, s, "DISABLED", "Portal user deactivated");
    }

    private static void setStatus(Exchange ctx, State s, String status, String message) {
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

    private static void delete(Exchange ctx, State s) {
        String id = ctx.pathParam("id");
        String clientId = ctx.queryParam("clientId");
        // No CLIENT_ID_REQUIRED here (spec §5.7: `Delete` only validates `ID_REQUIRED`) —
        // Checks already fails closed for a non-anchor with a blank/absent clientId,
        // and an anchor is entitled to delete by id alone.
        Checks.requirePortalUserManage(Auth.current(), clientId);
        DeletePortalIdentity.of(s.repo()).run(s.uow(), new DeleteCommand(clientId, id), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Portal user deleted"));
    }

    /// §4.3: `POST /api/portal-users/{id}/apps` — inactive ⇒ 400 `PORTAL_APP_INACTIVE`.
    private static void grantApp(Exchange ctx, State s) {
        var body = ctx.bodyAsClass(PortalUserAppGrantBody.class);
        if (body.clientId() == null || body.clientId().isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId is required");
        }
        Checks.requirePortalUserManage(Auth.current(), body.clientId());
        String id = ctx.pathParam("id");

        PortalApp app = resolveApp(s, body.clientId(), body.portalAppCode());
        if (!app.active()) {
            throw UseCaseException.validation("PORTAL_APP_INACTIVE", "portal app '" + app.code() + "' is inactive");
        }

        GrantPortalIdentityApp.of(s.repo(), s.portalApps())
                .run(s.uow(), new GrantPortalIdentityAppCommand(body.clientId(), id, app.id()), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Portal app access granted"));
    }

    /// §4.3: `DELETE /api/portal-users/{id}/apps/{portalAppCode}` — no inactive check (revoke always allowed).
    private static void revokeApp(Exchange ctx, State s) {
        String clientId = ctx.queryParam("clientId");
        if (clientId == null || clientId.isBlank()) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId is required");
        }
        Checks.requirePortalUserManage(Auth.current(), clientId);
        String id = ctx.pathParam("id");
        PortalApp app = resolveApp(s, clientId, ctx.pathParam("portalAppCode"));

        RevokePortalIdentityApp.of(s.repo(), s.portalApps())
                .run(s.uow(), new RevokePortalIdentityAppCommand(clientId, id, app.id()), Auth.executionContext());
        ctx.json(new StatusChangeResponse("Portal app access revoked"));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Resolves `code` (normalised) to the client's portal app, or
    /// `PortalApp_NOT_FOUND` with the normalised code as the id (spec
    /// `portal-apps.md` §4.1 step 1, §4.2, §4.3).
    private static PortalApp resolveApp(State s, String clientId, String code) {
        String normalized = PortalAppCode.normalize(code);
        return s.portalApps().findByClientAndCode(clientId, code)
                .orElseThrow(() -> UseCaseException.resourceNotFound("PortalApp", normalized));
    }

    /// The registered redirect URIs of the client's portal OAuth clients,
    /// ordered with `appId`'s own OAuth clients first (stable) when an app
    /// was resolved (spec §4.1 step 2).
    private static List<String> portalRedirectUrisFor(State s, String clientId, String appId) {
        List<OAuthClient> portalClients = s.oauthClients().findAll().stream()
                .filter(c -> clientId.equals(c.portalClientId()))
                .toList();
        List<OAuthClient> ordered;
        if (appId != null) {
            List<OAuthClient> appOwned = portalClients.stream().filter(c -> appId.equals(c.portalAppId())).toList();
            List<OAuthClient> rest = portalClients.stream().filter(c -> !appId.equals(c.portalAppId())).toList();
            ordered = new ArrayList<>(appOwned.size() + rest.size());
            ordered.addAll(appOwned);
            ordered.addAll(rest);
        } else {
            ordered = portalClients;
        }
        return ordered.stream().flatMap(c -> c.redirectUris().stream()).toList();
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

    /// Absent/blank/unparsable ⇒ 0 — the caller applies its own clamp rule.
    private static int intParam(Exchange ctx, String name) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── Wire DTOs (lockfile shapes) ──────────────────────────────────────────

    public record PortalUserRequest(String clientId, String email, String name, Boolean returnInviteLink,
                                     String redirectUri, String portalAppCode) {
    }

    public record PortalUserClientBody(String clientId) {
    }

    public record PortalUserAppGrantBody(String clientId, String portalAppCode) {
    }

    public record PortalUserResponse(
            String identityId, boolean created, boolean invited, String inviteUrl, Boolean ssoManaged,
            boolean hasPassword, String portalAppCode, String state) {
    }

    public record PortalUserAppRef(String id, String code, String name, String source, Instant grantedAt) {
        /// `code` / `name` fall back to the app id when the grant's app can't
        /// be resolved (spec §4.2).
        static PortalUserAppRef from(PortalAppGrant g, Map<String, PortalApp> apps) {
            PortalApp app = apps.get(g.appId());
            return new PortalUserAppRef(g.appId(), app != null ? app.code() : g.appId(),
                    app != null ? app.name() : g.appId(), g.source().name(), g.grantedAt());
        }
    }

    public record PortalUserListItem(
            String identityId, String email, String name, String status, String state, String source,
            boolean hasPassword, List<PortalUserAppRef> apps, Instant invitedAt, Instant inviteExpiresAt,
            Instant lastLoginAt, Instant createdAt, Instant updatedAt) {

        public PortalUserListItem {
            apps = apps == null ? List.of() : List.copyOf(apps);
        }

        /// `apps` ordered by `grantedAt` — already the order [PortalIdentity#apps] carries.
        static PortalUserListItem from(PortalIdentity pi, Map<String, PortalApp> apps, Instant now) {
            List<PortalUserAppRef> refs = pi.apps().stream().map(g -> PortalUserAppRef.from(g, apps)).toList();
            return new PortalUserListItem(pi.id(), pi.email(), pi.name() == null ? "" : pi.name(), // name is required on the wire; Go writes ""
                    pi.status().name(), pi.state(now).name(), pi.source().name(), pi.canSignInWithPassword(), refs,
                    pi.invitedAt(), pi.inviteExpiresAt(), pi.lastLoginAt(), pi.createdAt(), pi.updatedAt());
        }
    }

    public record PortalUserListResponse(List<PortalUserListItem> portalUsers, long total, int page, int size) {
        public PortalUserListResponse {
            portalUsers = portalUsers == null ? List.of() : List.copyOf(portalUsers);
        }
    }

    public record StatusChangeResponse(String message) {
    }
}

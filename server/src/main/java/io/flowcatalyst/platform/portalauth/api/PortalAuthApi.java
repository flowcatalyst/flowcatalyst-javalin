package io.flowcatalyst.platform.portalauth.api;

import io.flowcatalyst.platform.auth.grant.AuthorizationCode;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.oauth.OAuthError;
import io.flowcatalyst.platform.auth.oauth.RedirectUriMatcher;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalauth.PortalLoginFlow;
import io.flowcatalyst.platform.portalauth.PortalLoginFlowRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentityStatus;
import io.flowcatalyst.platform.portalidentity.PortalInviteEmailer;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/// The portal plane's own auth surface (spec `auth-identity.md` §3.2,
/// §5.1–§5.5): `/portal/authorize`, `/portal/auth/check-domain`,
/// `/portal/auth/login`, `/portal/auth/password-reset`. Entirely public —
/// no bearer, no cookie, ever (`Platform.isPublicPath` routes every
/// `/portal/*` request around the authenticator). Outside the lockfile
/// (spec tables are the contract, not `openapi.lock.json`).
///
/// Not registered here (spec §5.6, owned by the orchestrator wiring this
/// repository against the bridge): `GET /portal/auth/oidc/login` and the
/// OIDC callback's portal sink.
public final class PortalAuthApi {

    private static final Logger LOG = LoggerFactory.getLogger(PortalAuthApi.class);

    private PortalAuthApi() {
    }

    public record State(PortalLoginFlowRepository flows, OAuthClientRepository oauthClients,
                        PortalIdentityRepository identities, IdentityProviderRepository identityProviders,
                        GrantStore grantStore, RateLimit.Store rateLimitStore, RateLimit.Policies policies,
                        PortalInviteEmailer emailer, PortalAppRepository portalApps) {
        public State {
            Objects.requireNonNull(flows, "flows");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(identities, "identities");
            Objects.requireNonNull(identityProviders, "identityProviders");
            Objects.requireNonNull(grantStore, "grantStore");
            Objects.requireNonNull(policies, "policies");
            Objects.requireNonNull(emailer, "emailer");
            Objects.requireNonNull(portalApps, "portalApps");
        }
    }

    public static void register(Routes routes, State s) {
        routes.get("/portal/authorize", ctx -> authorize(ctx, s));
        routes.post("/portal/auth/check-domain", ctx -> checkDomain(ctx, s));
        routes.post("/portal/auth/login", ctx -> login(ctx, s));
        routes.post("/portal/auth/password-reset", ctx -> passwordReset(ctx, s));
    }

    // ── GET /portal/authorize (spec §5.1) ───────────────────────────────────

    private static void authorize(Exchange ctx, State s) {
        String state = ctx.queryParam("state");
        if (state == null || state.isBlank()) {
            OAuthError.invalidRequest("`state` parameter is required for CSRF protection").writePlain(ctx); // Go writes the plain envelope here, no cache headers (parity S3)
            return;
        }
        String clientId = ctx.queryParam("client_id");
        OAuthClient client;
        try {
            client = clientId == null ? null : s.oauthClients().findByClientId(clientId).orElse(null);
        } catch (RuntimeException e) {
            LOG.error("client lookup failed for /portal/authorize", e);
            OAuthError.serverError("Internal error").writePlain(ctx); // Go writes the plain envelope here, no cache headers (parity S3)
            return;
        }
        if (client == null || !client.active()) {
            OAuthError.unauthorizedClient(400, "Unknown or inactive client").writePlain(ctx); // Go writes the plain envelope here, no cache headers (parity S3)
            return;
        }
        if (!client.isPortal()) {
            OAuthError.unauthorizedClient(400, "Client is not a portal client").writePlain(ctx); // Go writes the plain envelope here, no cache headers (parity S3)
            return;
        }
        String redirectUri = ctx.queryParam("redirect_uri");
        if (redirectUri == null || !RedirectUriMatcher.matches(redirectUri, client.redirectUris())) {
            OAuthError.invalidRequest("Invalid redirect_uri").writePlain(ctx); // Go writes the plain envelope here, no cache headers (parity S3)
            return;
        }

        String responseType = ctx.queryParam("response_type");
        String codeChallenge = blankToNull(ctx.queryParam("code_challenge"));
        String codeChallengeMethod = blankToNull(ctx.queryParam("code_challenge_method"));

        if (!"code".equals(responseType)) {
            errRedirect(ctx, redirectUri, "unsupported_response_type", "Only 'code' response type is supported", state);
            return;
        }
        if (client.pkceRequired() && codeChallenge == null) {
            errRedirect(ctx, redirectUri, "invalid_request", "PKCE code_challenge is required", state);
            return;
        }
        if (codeChallengeMethod != null && !"S256".equals(codeChallengeMethod)) {
            errRedirect(ctx, redirectUri, "invalid_request", "Only the S256 code_challenge_method is supported", state);
            return;
        }

        String method = codeChallenge != null ? (codeChallengeMethod == null ? "S256" : codeChallengeMethod) : codeChallengeMethod;
        PortalLoginFlow flow = PortalLoginFlow.start(client.clientId(), client.portalClientId(), redirectUri,
                blankToNull(ctx.queryParam("scope")), state, blankToNull(ctx.queryParam("nonce")),
                codeChallenge, method, Instant.now());
        try {
            s.flows().insert(flow);
        } catch (RuntimeException e) {
            LOG.error("could not start portal login flow", e);
            errRedirect(ctx, redirectUri, "server_error", "Could not start the login flow", state);
            return;
        }
        ctx.status(307).header("Location", "/portal/login?flow=" + urlEncode(flow.id()));
    }

    // ── POST /portal/auth/check-domain (spec §5.2) ──────────────────────────

    private static void checkDomain(Exchange ctx, State s) {
        CheckDomainRequest req = readBody(ctx, CheckDomainRequest.class);
        PortalLoginFlow flow = liveFlow(s, req.flowId());
        String domain = domainOf(req.email());
        if (domain == null) {
            throw UseCaseException.validation("EMAIL_INVALID", "email is not valid");
        }
        Optional<IdentityProvider> idp = guarded(() -> ssoIdpFor(s, domain), "IDP", "identity provider lookup failed");
        if (idp.isPresent()) {
            ctx.json(new CheckDomainResponse("SSO",
                    "/portal/auth/oidc/login?flow=" + urlEncode(flow.id()) + "&provider_id=" + urlEncode(idp.get().id())));
            return;
        }
        ctx.json(new CheckDomainResponse("PASSWORD", null));
    }

    // ── POST /portal/auth/login (spec §5.3) ─────────────────────────────────

    private static void login(Exchange ctx, State s) {
        LoginRequest req = readBody(ctx, LoginRequest.class);
        PortalLoginFlow flow = liveFlow(s, req.flowId());
        String email = req.email() == null ? "" : req.email().trim().toLowerCase(Locale.ROOT);
        String password = req.password() == null ? "" : req.password();

        String rlKey = flow.portalClientId() + ":" + email;
        var rejection = RateLimit.enforce(s.rateLimitStore(), RateLimit.Bucket.PORTAL_LOGIN, rlKey, s.policies().portalLogin());
        if (rejection != null) {
            ctx.header("Retry-After", Long.toString(rejection.retryAfterSecs()));
            HttpError.write(ctx, 429, "TOO_MANY_REQUESTS", "too many attempts", java.util.Map.of());
            return;
        }

        String domain = domainOf(email);
        if (domain != null) {
            Optional<IdentityProvider> idp = guarded(() -> ssoIdpFor(s, domain), "IDP", "identity provider lookup failed");
            if (idp.isPresent()) {
                ctx.status(401).json(new CodeMessage("SSO_REQUIRED", "Sign in with your organisation account"));
                return;
            }
        }

        Optional<PortalIdentity> identity = guarded(() -> s.identities().findByClientAndEmail(flow.portalClientId(), email),
                "IDENTITY", "identity lookup failed");

        if (identity.isEmpty() || !identity.get().canSignInWithPassword()) {
            PasswordHash.equalizeTiming(password);
            ctx.status(401).json(new CodeMessage("INVALID_CREDENTIALS", "Invalid email or password"));
            return;
        }
        if (!PasswordHash.matches(password, identity.get().passwordHash())) {
            ctx.status(401).json(new CodeMessage("INVALID_CREDENTIALS", "Invalid email or password"));
            return;
        }

        // Portal-app gate (`docs/spec/portal-apps.md` §5.1): runs ONLY after a
        // successful password verify, and before the flow is consumed — a
        // denied caller must be able to retry the very same flow once granted.
        var app = guarded(() -> s.portalApps().findByOAuthClientId(flow.oauthClientId()), "PORTAL_APP", "portal app lookup failed");
        if (app.isPresent() && (!app.get().active() || !identity.get().hasApp(app.get().id()))) {
            ctx.status(403).json(new CodeMessage("NO_PORTAL_ACCESS", "You don't have access to this portal"));
            return;
        }

        PortalLoginFlow consumed = s.flows().consume(flow.id())
                .orElseThrow(() -> UseCaseException.validation("FLOW_EXPIRED",
                        "The login flow has expired — return to the portal and try again"));

        AuthorizationCode code = AuthorizationCode
                .issue(consumed.oauthClientId(), identity.get().id(), consumed.redirectUri(), Instant.now())
                .withScope(consumed.scope())
                .withNonce(consumed.nonce())
                .withState(consumed.state())
                .withPkce(consumed.codeChallenge(), consumed.codeChallengeMethod());
        try {
            s.grantStore().insert(code);
        } catch (RuntimeException e) {
            throw UseCaseException.internal("CODE", "could not issue the authorization code", e);
        }

        try {
            s.identities().touchLastLogin(identity.get().id());
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("touchLastLogin failed")
                    .addKeyValue("identity", identity.get().id())
                    .setCause(e)
                    .log();
        }

        String sep = consumed.redirectUri().contains("?") ? "&" : "?";
        String redirectUrl = consumed.redirectUri() + sep + "code=" + urlEncode(code.code()) + "&state=" + urlEncode(consumed.state());
        ctx.json(new LoginResponse(redirectUrl));
    }

    // ── POST /portal/auth/password-reset (spec §5.4) ────────────────────────

    private static void passwordReset(Exchange ctx, State s) {
        PasswordResetRequest req = readBody(ctx, PasswordResetRequest.class);
        PortalLoginFlow flow = liveFlow(s, req.flowId());
        String email = req.email() == null ? "" : req.email().trim().toLowerCase(Locale.ROOT);
        String domain = domainOf(email);
        if (domain == null) {
            throw UseCaseException.validation("EMAIL_INVALID", "email is not valid");
        }

        String rlKey = "reset:" + flow.portalClientId() + ":" + email;
        var rejection = RateLimit.enforce(s.rateLimitStore(), RateLimit.Bucket.PORTAL_LOGIN, rlKey, s.policies().portalLogin());
        if (rejection == null) {
            try {
                if (ssoIdpFor(s, domain).isEmpty()) {
                    Optional<PortalIdentity> identity = s.identities().findByClientAndEmail(flow.portalClientId(), email);
                    if (identity.isPresent() && identity.get().status() == PortalIdentityStatus.ACTIVE) {
                        String origin = portalOriginOf(flow.redirectUri());
                        if (origin != null) {
                            s.emailer().sendPortalReset(identity.get().id(), identity.get().email(), origin);
                        }
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("portal password-reset best-effort send failed", e);
            }
        }
        ctx.json(new MessageResponse("If an account exists, a reset email has been sent."));
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    private static <T> T readBody(Exchange ctx, Class<T> type) {
        try {
            return ctx.bodyAsClass(type);
        } catch (RuntimeException e) {
            throw UseCaseException.validation("INVALID_BODY", "malformed request body");
        }
    }

    private static PortalLoginFlow liveFlow(State s, String flowId) {
        if (flowId == null || flowId.isBlank()) {
            throw UseCaseException.validation("FLOW_EXPIRED", "The login flow has expired — return to the portal and try again");
        }
        Optional<PortalLoginFlow> flow = guarded(() -> s.flows().findLive(flowId), "FLOW", "login flow lookup failed");
        return flow.orElseThrow(() -> UseCaseException.validation("FLOW_EXPIRED",
                "The login flow has expired — return to the portal and try again"));
    }

    /// Runs a repository read, mapping any infrastructure failure to the
    /// named internal error code (spec §5.2–§5.4's `FLOW` / `IDP` /
    /// `IDENTITY`) rather than letting a raw jOOQ exception surface as
    /// bare `INTERNAL`.
    private static <T> T guarded(Supplier<T> op, String code, String message) {
        try {
            return op.get();
        } catch (UseCaseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw UseCaseException.internal(code, message, e);
        }
    }

    /// `@` at an index > 0 and not last, lower-cased (spec §5.2).
    private static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            return null;
        }
        return email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    /// An OIDC identity provider whose derived `allowedEmailDomains` names
    /// `domain`, case-insensitively (spec §5.2, §5.3, §5.4).
    private static Optional<IdentityProvider> ssoIdpFor(State s, String domain) {
        return s.identityProviders().findAll().stream()
                .filter(idp -> idp.type() == IdentityProviderType.OIDC)
                .filter(idp -> idp.allowedEmailDomains().stream().anyMatch(d -> d.equalsIgnoreCase(domain)))
                .findFirst();
    }

    /// `scheme://host/` of a validated redirect URI, `null` when unparsable
    /// or the host is a wildcard pattern (spec §5.4).
    private static String portalOriginOf(String redirectUri) {
        if (redirectUri == null || redirectUri.indexOf('*') >= 0) {
            return null;
        }
        try {
            URI uri = new URI(redirectUri);
            String host = uri.getHost();
            if (uri.getScheme() == null || host == null || host.isEmpty()) {
                return null;
            }
            String authority = uri.getPort() == -1 ? host : host + ":" + uri.getPort();
            return uri.getScheme() + "://" + authority + "/";
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /// 307-redirects to `redirectUri` with `error`, `error_description`,
    /// `state` appended (spec §5.1: post-validation failures).
    private static void errRedirect(Exchange ctx, String redirectUri, String error, String description, String state) {
        String sep = redirectUri.contains("?") ? "&" : "?";
        String url = redirectUri + sep + "error=" + urlEncode(error)
                + "&error_description=" + urlEncode(description)
                + "&state=" + urlEncode(state);
        ctx.status(307).header("Location", url);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────────

    public record CheckDomainRequest(String flowId, String email) {
    }

    public record CheckDomainResponse(String method, String redirectUrl) {
    }

    public record LoginRequest(String flowId, String email, String password) {
    }

    public record LoginResponse(String redirectUrl) {
    }

    public record PasswordResetRequest(String flowId, String email) {
    }

    public record MessageResponse(String message) {
    }

    /// The `{"code": ..., "message": ...}` shape spec §5.3 uses for
    /// `SSO_REQUIRED` / `INVALID_CREDENTIALS` — deliberately not the
    /// platform's `{"error": ..., "message": ...}` envelope; this is the
    /// literal wire shape the spec table pins for these two responses.
    public record CodeMessage(String code, String message) {
    }
}

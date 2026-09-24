package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.grant.AuthorizationCode;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// `GET /oauth/authorize` (`docs/spec/auth-core.md` §6.2 O1, §7.3.1 with
/// the §0.5 rulings; Go `oauthapi.Authorize`). Direct 4xx until the client
/// and its `redirect_uri` are trusted (RFC 6749 §4.1.2.1 — never bounce to
/// an unverified URI); from there errors redirect back with
/// `error&error_description&state`. The per-client 429 is the RFC shape
/// (ruling C-Q27); a `state` over 116 characters is `invalid_request`
/// before any write (ruling C-Q22); the session cookie wins over a Bearer
/// (ruling C-Q25), and either must carry a session token for an active
/// principal (S2.2); `auth_time` is the session's issue time (ruling C-Q1).
public final class OAuthAuthorizeApi {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthAuthorizeApi.class);

    /// VARCHAR(128) minus the `PendingAuth:` prefix.
    static final int MAX_STATE_LENGTH = 116;

    private static final Set<String> STANDARD_SCOPES = Set.of("openid", "profile", "email", "offline_access");
    private static final Pattern CLIENT_HINT = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$");

    private OAuthAuthorizeApi() {
    }

    /// `sessionCookie` is NOT part of [OAuthState] — every other OAuth/OIDC
    /// endpoint shares that one record and none of the rest touch the
    /// session cookie at all; threading it through just this route keeps
    /// `docs/spec/cookie-hardening.md` §3's "one decision" (the SAME
    /// `cookiesSecure` `Platform` builds every `SessionCookie` from) without
    /// widening the shared state every other handler here carries.
    public static void register(Routes routes, OAuthState s, SessionCookie sessionCookie) {
        // Group.OIDC (admission.md §11.7 part B follow-up): authenticates the client
        // and the caller's session, then mints an authorization code.
        routes.in(Group.OIDC).get("/oauth/authorize", ctx -> authorize(ctx, s, sessionCookie));
    }

    static void authorize(Exchange ctx, OAuthState s, SessionCookie sessionCookie) {
        String responseType = q(ctx, "response_type");
        String clientId = q(ctx, "client_id");
        String redirectUri = q(ctx, "redirect_uri");
        String scope = q(ctx, "scope");
        String state = q(ctx, "state");
        String nonce = q(ctx, "nonce");
        String codeChallenge = q(ctx, "code_challenge");
        String codeChallengeMethod = q(ctx, "code_challenge_method");
        String providerId = q(ctx, "provider");
        String prompt = q(ctx, "prompt");
        String maxAge = q(ctx, "max_age");
        String clientHint = sanitizeClientHint(q(ctx, "client"));

        if (state.isEmpty()) {
            OAuthError.invalidRequest("`state` parameter is required for CSRF protection").write(ctx);
            return;
        }
        var rej = RateLimit.enforce(s.rateLimit(), RateLimit.Bucket.OAUTH_AUTHORIZE_CLIENT, clientId, s.policies().oauthAuthorizeClient());
        if (rej != null) {
            OAuthError.writeRateLimited(ctx, rej.retryAfterSecs(), "rate limit exceeded");
            return;
        }

        Optional<OAuthClient> found;
        try {
            found = s.oauthClients().findByClientId(clientId);
        } catch (RuntimeException e) {
            LOG.atError().setMessage("oauth client lookup failed")
                    .addKeyValue("oauth_client_id", clientId)
                    .setCause(e)
                    .log();
            OAuthError.serverError("Internal error").write(ctx);
            return;
        }
        if (found.isEmpty()) {
            OAuthError.unauthorizedClient(400, "Unknown client").write(ctx);
            return;
        }
        OAuthClient client = found.get();
        if (!client.active()) {
            OAuthError.unauthorizedClient(400, "Client is not active").write(ctx);
            return;
        }
        if (!RedirectUriMatcher.matches(redirectUri, client.redirectUris())) {
            OAuthError.invalidRequest("Invalid redirect_uri").write(ctx);
            return;
        }
        if (state.length() > MAX_STATE_LENGTH) {
            errorRedirect(ctx, redirectUri, "invalid_request", "`state` must be at most 116 characters", state);
            return;
        }
        if (client.isPortal()) {
            OAuthError.unauthorizedClient(400, "Portal clients must use /portal/authorize").write(ctx);
            return;
        }

        // The client and redirect_uri are trusted from here on.
        if (!"code".equals(responseType)) {
            errorRedirect(ctx, redirectUri, "unsupported_response_type", "Only 'code' response type is supported", state);
            return;
        }
        if (!client.allowsGrant("authorization_code")) {
            errorRedirect(ctx, redirectUri, "unauthorized_client", "Client is not permitted to use the authorization_code grant", state);
            return;
        }
        if (client.pkceRequired() && codeChallenge.isEmpty()) {
            errorRedirect(ctx, redirectUri, "invalid_request", "PKCE code_challenge is required", state);
            return;
        }
        if (!codeChallengeMethod.isEmpty() && !"S256".equals(codeChallengeMethod)) {
            errorRedirect(ctx, redirectUri, "invalid_request", "Only the S256 code_challenge_method is supported", state);
            return;
        }
        if (!scope.isEmpty()) {
            List<String> invalid = invalidScopes(scope, client.defaultScopes());
            if (!invalid.isEmpty()) {
                errorRedirect(ctx, redirectUri, "invalid_scope", "Invalid scope(s): " + String.join(", ", invalid), state);
                return;
            }
        }

        Optional<TokenClaims> session;
        try {
            session = session(ctx, s, sessionCookie);
        } catch (RuntimeException e) {
            LOG.atError().setMessage("session principal lookup failed")
                    .addKeyValue("oauth_client_id", clientId)
                    .setCause(e)
                    .log();
            errorRedirect(ctx, redirectUri, "server_error", "Internal error", state);
            return;
        }
        Instant issuedAt = session.map(TokenClaims::issuedAt).orElse(null);
        boolean sessionOk = session.isPresent();
        boolean stale = sessionOk && maxAgeExceeded(maxAge, issuedAt, s.clock().instant());

        boolean forceLogin = false;
        switch (prompt) {
            case "none" -> {
                if (!sessionOk || stale) {
                    errorRedirect(ctx, redirectUri, "login_required", "User is not authenticated", state);
                    return;
                }
            }
            case "login" -> forceLogin = true;
            default -> { }
        }

        if (!forceLogin && sessionOk && !stale) {
            Instant now = s.clock().instant();
            var code = AuthorizationCode.issue(clientId, session.get().subject(), redirectUri, now)
                    .withScope(blankToNull(scope)).withNonce(blankToNull(nonce)).withState(state)
                    .withAuthTime(issuedAt);
            if (!codeChallenge.isEmpty()) {
                // Persisted unconditionally so PKCE can never be stripped by
                // omitting the method; an absent method is S256.
                code = code.withPkce(codeChallenge, codeChallengeMethod.isEmpty() ? "S256" : codeChallengeMethod);
            }
            try {
                s.grants().insert(code);
            } catch (RuntimeException e) {
                LOG.error("authorization code insert failed", e);
                errorRedirect(ctx, redirectUri, "server_error", "Failed to create authorization code", state);
                return;
            }
            ctx.redirect(redirectUri + querySep(redirectUri) + "code=" + pct(code.code()) + "&state=" + pct(state),
                    307);
            return;
        }

        if (!providerId.isEmpty()) {
            // Provider-direct entry: chain straight into the OIDC bridge,
            // which carries the whole OAuth chain in its login state.
            StringBuilder bridge = new StringBuilder("/auth/oidc/login?provider_id=").append(pct(providerId))
                    .append("&oauth_client_id=").append(pct(clientId))
                    .append("&oauth_redirect_uri=").append(pct(redirectUri))
                    .append("&oauth_state=").append(pct(state));
            if (!scope.isEmpty()) bridge.append("&oauth_scope=").append(pct(scope));
            if (!codeChallenge.isEmpty()) bridge.append("&oauth_code_challenge=").append(pct(codeChallenge));
            if (!codeChallengeMethod.isEmpty()) bridge.append("&oauth_code_challenge_method=").append(pct(codeChallengeMethod));
            if (!nonce.isEmpty()) bridge.append("&oauth_nonce=").append(pct(nonce));
            ctx.redirect(bridge.toString(), 307);
            return;
        }

        // Not authenticated: stash (wire compatibility) and bounce to the login page.
        try {
            s.grants().insertPendingAuth(state, new GrantStore.PendingAuth(clientId, redirectUri, blankToNull(scope),
                    blankToNull(codeChallenge), blankToNull(codeChallengeMethod), blankToNull(nonce), s.clock().instant()));
        } catch (RuntimeException e) {
            LOG.error("pending auth insert failed", e);
            errorRedirect(ctx, redirectUri, "server_error", "Internal error", state);
            return;
        }
        StringBuilder login = new StringBuilder("/auth/login?oauth=true&response_type=code")
                .append("&client_id=").append(pct(clientId))
                .append("&redirect_uri=").append(pct(redirectUri))
                .append("&state=").append(pct(state));
        if (!scope.isEmpty()) login.append("&scope=").append(pct(scope));
        if (!codeChallenge.isEmpty()) login.append("&code_challenge=").append(pct(codeChallenge));
        if (!codeChallengeMethod.isEmpty()) login.append("&code_challenge_method=").append(pct(codeChallengeMethod));
        if (!nonce.isEmpty()) login.append("&nonce=").append(pct(nonce));
        if (!clientHint.isEmpty()) login.append("&client=").append(pct(clientHint));
        ctx.redirect(login.toString(), 307);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /// The signed-in user: the session cookie first, then a Bearer (ruling
    /// C-Q25 keeps both orders) — but whichever carried it, the token must be
    /// the **session kind** ([TokenClaims#isSessionToken]) and its principal
    /// must exist and be active (`docs/spec/security-fixes-2026-09-24.md`
    /// S2.2). An API or identity access token is never a sign-in: it may be
    /// narrowed, delegated to an OAuth client, or belong to a service account,
    /// and a code minted from it would hand a relying party a user session
    /// nobody signed in to. Anything else is simply "no session" — the caller
    /// is sent to log in, exactly as with no credential.
    private static Optional<TokenClaims> session(Exchange ctx, OAuthState s, SessionCookie sessionCookie) {
        String sessionToken = ctx.cookie(sessionCookie.name());
        if (sessionToken == null || sessionToken.isEmpty()) {
            sessionToken = AccessTokenReader.bearer(ctx.header("Authorization"));
        }
        Optional<TokenClaims> claims = s.tokens().read(sessionToken)
                .map(AccessTokenReader.Read::claims)
                .filter(TokenClaims::isSessionToken);
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        boolean active = s.principals().findById(claims.get().subject()).map(Principal::active).orElse(false);
        return active ? claims : Optional.empty();
    }

    /// An absent or invalid `max_age`, or an unknown issue time, never forces
    /// a re-login; `max_age=0` always does.
    static boolean maxAgeExceeded(String maxAge, Instant issuedAt, Instant now) {
        if (maxAge == null || maxAge.isEmpty() || issuedAt == null) {
            return false;
        }
        long secs;
        try {
            secs = Long.parseLong(maxAge);
        } catch (NumberFormatException e) {
            return false;
        }
        if (secs < 0) {
            return false;
        }
        return Duration.between(issuedAt, now).compareTo(Duration.ofSeconds(secs)) > 0;
    }

    static List<String> invalidScopes(String scope, List<String> clientScopes) {
        var invalid = new ArrayList<String>();
        for (String sc : scope.trim().split("\\s+")) {
            if (sc.isEmpty() || STANDARD_SCOPES.contains(sc) || clientScopes.contains(sc)) {
                continue;
            }
            invalid.add(sc);
        }
        return invalid;
    }

    static String sanitizeClientHint(String v) {
        String t = v == null ? "" : v.trim();
        return t.isEmpty() || t.length() > 64 || !CLIENT_HINT.matcher(t).matches() ? "" : t;
    }

    static void errorRedirect(Exchange ctx, String redirectUri, String code, String description, String state) {
        String url = redirectUri + querySep(redirectUri) + "error=" + pct(code) + "&error_description=" + pct(description);
        if (state != null && !state.isEmpty()) {
            url += "&state=" + pct(state);
        }
        ctx.redirect(url, 307);
    }

    static String querySep(String redirectUri) {
        return redirectUri.contains("?") ? "&" : "?";
    }

    /// RFC 3986 percent-encoding: the unreserved set preserved, `%20` for space.
    static String pct(String s) {
        final String hex = "0123456789ABCDEF";
        var b = new StringBuilder();
        for (byte raw : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append((char) c);
            } else {
                b.append('%').append(hex.charAt(c >> 4)).append(hex.charAt(c & 0x0F));
            }
        }
        return b.toString();
    }

    private static String q(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null ? "" : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }
}

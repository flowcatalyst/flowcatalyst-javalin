package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.grant.AuthorizationCode;
import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.flowcatalyst.platform.auth.token.ScopeNarrowing;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalType;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// `POST /oauth/token` (`docs/spec/auth-core.md` §6.2 O2, §6.2a with the
/// §0.5 rulings; Go `oauthapi.Token`): the three grants plus the developer
/// branch of `client_credentials`. Errors are RFC 6749 bodies; every one is
/// 400 except `invalid_client` (401, ruling A-15). Basic credentials are
/// normalised into the request first (ruling A-13) so `client_credentials`
/// honours `client_secret_basic` and the per-client throttle sees the real
/// identity (ruling A-14). An empty grant list permits nothing (ruling
/// C-Q20). `expires_in` is the configured TTL, never a literal (A-23).
public final class OAuthTokenApi {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthTokenApi.class);

    /// The seeded role that gates the developer client_credentials branch.
    static final String DEVELOPER_ROLE = "platform:developer";

    private OAuthTokenApi() {
    }

    public static void register(JavalinDefaultRoutingApi routes, OAuthState s) {
        routes.post("/oauth/token", ctx -> token(ctx, s));
    }

    record TokenRequest(String grantType, String code, String redirectUri, String clientId, String clientSecret,
                        String codeVerifier, String refreshToken, String scope) {
        static TokenRequest of(Context ctx) {
            return new TokenRequest(form(ctx, "grant_type"), form(ctx, "code"), form(ctx, "redirect_uri"),
                    form(ctx, "client_id"), form(ctx, "client_secret"), form(ctx, "code_verifier"),
                    form(ctx, "refresh_token"), form(ctx, "scope"));
        }

        TokenRequest withClient(String id, String secret) {
            return new TokenRequest(grantType, code, redirectUri, id, secret, codeVerifier, refreshToken, scope);
        }
    }

    static void token(Context ctx, OAuthState s) {
        TokenRequest req;
        try {
            req = TokenRequest.of(ctx);
        } catch (RuntimeException e) {
            OAuthError.invalidRequest("Malformed form body").write(ctx);
            return;
        }

        // client_secret_basic resolved up front; two client identities in
        // one request are refused (RFC 6749 §3.2.1).
        var basic = ClientAuthentication.basicCredentials(ctx);
        if (basic.isPresent()) {
            if (!req.clientId().isEmpty() && !req.clientId().equals(basic.get().clientId())) {
                OAuthError.invalidRequest("client_id does not match the authenticated client").write(ctx);
                return;
            }
            req = req.withClient(basic.get().clientId(), basic.get().clientSecret());
        }

        // Per-client throttle: the local governor sheds a flood first, then
        // the cluster-wide store. Composes with the per-IP layer.
        if (!req.clientId().isEmpty()) {
            if (s.clientGovernor() != null) {
                var check = s.clientGovernor().check(req.clientId());
                if (!check.ok()) {
                    OAuthError.writeRateLimited(ctx, check.retryAfterSecs(), "this client_id has exceeded its token endpoint rate limit");
                    return;
                }
            }
            var rej = RateLimit.enforce(s.rateLimit(), RateLimit.Bucket.OAUTH_TOKEN_CLIENT, req.clientId(), s.policies().oauthTokenClient());
            if (rej != null) {
                OAuthError.writeRateLimited(ctx, rej.retryAfterSecs(), "this client_id has exceeded its token endpoint rate limit");
                return;
            }
        }

        OAuthClient authenticated = null;
        if (!"client_credentials".equals(req.grantType())) {
            var result = ClientAuthentication.authenticateClient(s, ctx, req.clientId(), req.clientSecret());
            if (result.failed()) {
                result.error().write(ctx);
                return;
            }
            authenticated = result.client();
            if (!authenticated.allowsGrant(req.grantType())) {
                OAuthError.unauthorizedClient(400, "Client is not permitted to use the '" + req.grantType() + "' grant type").write(ctx);
                return;
            }
        }

        switch (req.grantType()) {
            case "authorization_code" -> authorizationCode(ctx, s, req, authenticated);
            case "refresh_token" -> refreshToken(ctx, s, req, authenticated);
            case "client_credentials" -> clientCredentials(ctx, s, req);
            default -> OAuthError.of(400, "unsupported_grant_type", "Grant type '" + req.grantType() + "' is not supported").write(ctx);
        }
    }

    // ── client_credentials ─────────────────────────────────────────────────

    private static void clientCredentials(Context ctx, OAuthState s, TokenRequest req) {
        if (req.clientId().isEmpty()) {
            OAuthError.invalidRequest("Missing client_id").write(ctx);
            return;
        }
        if (req.clientSecret().isEmpty()) {
            OAuthError.invalidRequest("Missing client_secret").write(ctx);
            return;
        }
        Optional<OAuthClient> found;
        try {
            found = s.oauthClients().findByClientId(req.clientId());
        } catch (RuntimeException e) {
            LOG.error("oauth client lookup failed client_id={}", req.clientId(), e);
            OAuthError.serverError("").write(ctx);
            return;
        }
        if (found.isEmpty()) {
            // A principal's own id as client_id is the self-service developer
            // credential; the prefixes (oac_ vs prn_) never collide.
            if (req.clientId().startsWith(EntityType.PRINCIPAL.prefix() + "_")) {
                developerCredential(ctx, s, req);
                return;
            }
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        OAuthClient client = found.get();
        if (!client.active()) {
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        if (client.clientType() != ClientType.CONFIDENTIAL) {
            OAuthError.unauthorizedClient(401, "Public clients cannot use client_credentials grant").write(ctx);
            return;
        }
        if (!client.allowsGrant("client_credentials")) {
            s.recordAttempt(AttemptType.SERVICE_ACCOUNT_TOKEN, AttemptOutcome.FAILURE, req.clientId(), null,
                    "client_credentials grant not permitted for this client");
            OAuthError.unauthorizedClient(401, "Client is not permitted to use the client_credentials grant type").write(ctx);
            return;
        }
        if (client.secretRef() == null) {
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        if (!ClientAuthentication.acceptClientSecret(s, client, req.clientSecret())) {
            s.recordAttempt(AttemptType.SERVICE_ACCOUNT_TOKEN, AttemptOutcome.FAILURE, req.clientId(), null, "Invalid client secret");
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        // A confidential client with no (or a dangling) linked principal is the
        // client's misconfiguration, not a server fault: 400 unauthorized_client
        // (RFC 6749 §5.2; owner ruling 2026-09-06 #11, Go 491d961 the same), and
        // the attempt is recorded like every other refusal on this grant.
        if (client.principalId() == null) {
            s.recordAttempt(AttemptType.SERVICE_ACCOUNT_TOKEN, AttemptOutcome.FAILURE, req.clientId(), null,
                    "Client not properly configured (no linked principal)");
            OAuthError.unauthorizedClient(400, "Client is not configured for this grant").write(ctx);
            return;
        }
        Optional<Principal> p = s.principals().findById(client.principalId());
        if (p.isEmpty()) {
            s.recordAttempt(AttemptType.SERVICE_ACCOUNT_TOKEN, AttemptOutcome.FAILURE, req.clientId(), null,
                    "Client not properly configured (linked principal not found)");
            OAuthError.unauthorizedClient(400, "Client is not configured for this grant").write(ctx);
            return;
        }
        if (!p.get().active()) {
            OAuthError.invalidClient("Service account is not active").write(ctx);
            return;
        }
        mintClientCredentials(ctx, s, p.get(), req, AttemptType.SERVICE_ACCOUNT_TOKEN, "the service account's granted permissions");
    }

    /// `client_id` = a USER principal's own id, `client_secret` = its
    /// dedicated developer secret; the developer role is re-checked live so
    /// revoking it cuts off new tokens immediately. Every failure is the same
    /// 401; only a wrong secret records a DEVELOPER_TOKEN failure.
    private static void developerCredential(Context ctx, OAuthState s, TokenRequest req) {
        Optional<Principal> found = s.principals().findById(req.clientId());
        if (found.isEmpty() || !found.get().active() || found.get().type() != PrincipalType.USER) {
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        Principal p = found.get();
        if (!p.roleNames().contains(DEVELOPER_ROLE)) {
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        String ref = p.userIdentity() == null ? null : p.userIdentity().devClientSecretRef();
        if (ref == null) {
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        if (!ClientAuthentication.verifySecretRef(s, ref, req.clientSecret())) {
            s.recordAttempt(AttemptType.DEVELOPER_TOKEN, AttemptOutcome.FAILURE, req.clientId(), p.id(), "Invalid developer client secret");
            OAuthError.invalidClient("Invalid client credentials").write(ctx);
            return;
        }
        mintClientCredentials(ctx, s, p, req, AttemptType.DEVELOPER_TOKEN, "your granted permissions");
    }

    private static void mintClientCredentials(Context ctx, OAuthState s, Principal p, TokenRequest req,
                                              AttemptType attemptType, String deniedScopeSubject) {
        var granted = ScopeNarrowing.grant(s.resolver().ceiling(p), req.scope());
        if (granted.explicit() && granted.permissions().isEmpty()) {
            s.recordAttempt(attemptType, AttemptOutcome.FAILURE, req.clientId(), p.id(), "requested scope exceeds granted permissions");
            OAuthError.invalidScope("Requested scope exceeds " + deniedScopeSubject).write(ctx);
            return;
        }
        String accessToken = s.issuer().accessToken(p, TokenIssuer.Authority.full(p, granted.permissions(), s.labels()), null);
        s.recordAttempt(attemptType, AttemptOutcome.SUCCESS, req.clientId(), p.id(), null);
        if (s.serviceAccounts() != null && p.serviceAccountId() != null) {
            try {
                s.serviceAccounts().touchLastUsed(p.serviceAccountId());
            } catch (RuntimeException e) {
                LOG.warn("service account last-used stamp failed id={}", p.serviceAccountId(), e);
            }
        }
        writeToken(ctx, s, accessToken, null, null, granted.claim());
    }

    // ── authorization_code ─────────────────────────────────────────────────

    private static void authorizationCode(Context ctx, OAuthState s, TokenRequest req, OAuthClient client) {
        if (req.code().isEmpty()) {
            OAuthError.invalidRequest("Missing 'code' parameter").write(ctx);
            return;
        }
        Optional<AuthorizationCode> consumed = s.grants().findAndConsume(req.code());
        if (consumed.isEmpty()) {
            OAuthError.invalidGrant("Invalid or expired authorization code").write(ctx);
            return;
        }
        AuthorizationCode code = consumed.get();
        Instant now = s.clock().instant();
        if (code.isExpired(now)) {
            OAuthError.invalidGrant("Authorization code has expired").write(ctx);
            return;
        }
        // Bound to the AUTHENTICATED client, never the body client_id.
        if (client == null || !client.clientId().equals(code.clientId())) {
            OAuthError.invalidGrant("Client ID mismatch").write(ctx);
            return;
        }
        if (!req.redirectUri().equals(code.redirectUri())) {
            OAuthError.invalidGrant("Redirect URI mismatch").write(ctx);
            return;
        }
        if (code.codeChallenge() != null) {
            OAuthError pkce = Pkce.verify(code.codeChallenge(), code.codeChallengeMethod(), req.codeVerifier());
            if (pkce != null) {
                pkce.write(ctx);
                return;
            }
        }
        if (code.principalId().startsWith(EntityType.PORTAL_USER.prefix() + "_")) {
            redeemPortalCode(ctx, s, code, client);
            return;
        }
        Optional<Principal> found = s.principals().findById(code.principalId());
        if (found.isEmpty()) {
            OAuthError.invalidGrant("Principal not found").write(ctx);
            return;
        }
        Principal p = found.get();
        String scope = code.scope() == null ? "" : code.scope();

        String accessToken = InteractiveMint.accessToken(s, p, client, scope);
        String idToken = scopeHas(scope, "openid") ? InteractiveMint.idToken(s, p, code.clientId(), client, code.nonce(), code.authTime()) : null;
        String refreshRaw = null;
        if (scopeHas(scope, "offline_access")) {
            RefreshToken.Issued issued = RefreshToken.issue(p.id(), now);
            // A new family rooted at this first token, bound to the code's
            // client, carrying the sign-in time forward.
            RefreshToken entity = issued.token()
                    .withBinding(code.clientId(), scopes(scope), List.of(), code.authTime())
                    .withFamily(issued.token().id());
            s.grants().insert(entity);
            refreshRaw = issued.raw();
        }
        writeToken(ctx, s, accessToken, refreshRaw, idToken, code.scope());
    }

    /// §5.8: the identity must exist and be ACTIVE; the access token is
    /// identity-only, the id_token (iff openid) carries empty roles, and
    /// there is never a refresh token.
    private static void redeemPortalCode(Context ctx, OAuthState s, AuthorizationCode code, OAuthClient client) {
        if (s.portalSubjects() == null) {
            OAuthError.invalidGrant("Portal subjects are not supported").write(ctx);
            return;
        }
        Optional<PortalSubjects.Subject> found;
        try {
            found = s.portalSubjects().findSubject(code.principalId());
        } catch (RuntimeException e) {
            LOG.error("portal identity lookup failed id={}", code.principalId(), e);
            OAuthError.serverError("").write(ctx);
            return;
        }
        if (found.isEmpty() || !found.get().active()) {
            OAuthError.invalidGrant("Portal identity not found or suspended").write(ctx);
            return;
        }
        PortalSubjects.Subject subject = found.get();
        Principal synth = Principal.portalSubject(subject.id(), subject.email(), subject.name());
        String accessToken = s.issuer().identityAccessToken(synth, client.clientId());
        String scope = code.scope() == null ? "" : code.scope();
        String idToken = null;
        if (scopeHas(scope, "openid")) {
            idToken = s.issuer().idToken(synth, new TokenIssuer.IdTokenInput(code.clientId(), code.nonce(), code.authTime(),
                    List.of(), List.of(), false, List.of()));
        }
        writeToken(ctx, s, accessToken, null, idToken, code.scope());
    }

    // ── refresh_token ──────────────────────────────────────────────────────

    private static void refreshToken(Context ctx, OAuthState s, TokenRequest req, OAuthClient authenticated) {
        if (req.refreshToken().isEmpty()) {
            OAuthError.invalidRequest("Missing refresh_token parameter").write(ctx);
            return;
        }
        RefreshRotation.Result result;
        try {
            result = s.rotation().rotate(req.refreshToken(), stored -> {
                // A token issued to a client may only be refreshed by that client.
                if (stored.oauthClientId() != null) {
                    String requesting = authenticated == null ? "" : authenticated.clientId();
                    if (!requesting.equals(stored.oauthClientId())) {
                        return "Token was not issued to this client";
                    }
                }
                return null;
            });
        } catch (RefreshRotation.NotAuthorized e) {
            OAuthError.invalidGrant("Token was not issued to this client").write(ctx);
            return;
        }
        if (result.stored().isEmpty()) {
            OAuthError.invalidGrant("Invalid or expired refresh token").write(ctx);
            return;
        }
        RefreshToken stored = result.stored().get();
        Optional<Principal> found = s.principals().findById(stored.principalId());
        if (found.isEmpty()) {
            OAuthError.invalidGrant("Principal not found").write(ctx);
            return;
        }
        Principal p = found.get();
        if (!p.active()) {
            OAuthError.invalidGrant("Account is not active").write(ctx);
            return;
        }
        // The refreshed access token follows the original's rule, re-derived
        // from the CURRENT principal so role changes take effect on refresh.
        OAuthClient refreshClient = authenticated;
        if (refreshClient == null && stored.oauthClientId() != null) {
            refreshClient = s.oauthClients().findByClientId(stored.oauthClientId()).orElse(null);
        }
        String scope = String.join(" ", stored.scopes());
        String accessToken = InteractiveMint.accessToken(s, p, refreshClient, scope);
        String idToken = null;
        if (stored.scopes().contains("openid") && stored.oauthClientId() != null) {
            try {
                idToken = InteractiveMint.idToken(s, p, stored.oauthClientId(), refreshClient, null, stored.authTime());
            } catch (RuntimeException e) {
                LOG.warn("id_token mint on refresh failed principal={}", p.id(), e); // non-fatal
            }
        }
        writeToken(ctx, s, accessToken, result.newRaw().orElse(null), idToken, scope.isEmpty() ? null : scope);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static void writeToken(Context ctx, OAuthState s, String accessToken, String refreshToken, String idToken, String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        body.put("token_type", "Bearer");
        body.put("expires_in", s.issuer().config().accessTtlSeconds());
        if (refreshToken != null) {
            body.put("refresh_token", refreshToken);
        }
        if (idToken != null) {
            body.put("id_token", idToken);
        }
        if (scope != null && !scope.isEmpty()) {
            body.put("scope", scope);
        }
        ctx.status(200)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .contentType("application/json")
                .result(Json.writeLine(body));
    }

    static boolean scopeHas(String scope, String want) {
        return scopes(scope).contains(want);
    }

    static List<String> scopes(String scope) {
        return scope == null || scope.isBlank() ? List.of() : Arrays.asList(scope.trim().split("\\s+"));
    }

    static String form(Context ctx, String name) {
        String v = ctx.formParam(name);
        return v == null ? "" : v;
    }
}

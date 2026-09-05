package io.flowcatalyst.platform.auth.oidc;

import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.oauth.OAuthError;
import io.flowcatalyst.platform.auth.oauth.RedirectUriMatcher;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.CreateCommand;
import io.flowcatalyst.platform.principal.operations.CreatePortalUser;
import io.flowcatalyst.platform.principal.operations.CreatePortalUserCommand;
import io.flowcatalyst.platform.principal.operations.CreateUser;
import io.flowcatalyst.platform.principal.operations.SyncIdpRoles;
import io.flowcatalyst.platform.principal.operations.SyncIdpRolesCommand;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// The OIDC bridge, employee plane (`docs/spec/auth-identity.md` §4 with
/// the §0.5 rulings): `GET /auth/oidc/login` starts a handshake and 302s
/// to the identity provider; `GET /auth/oidc/callback` consumes the state
/// exactly once, exchanges the code with PKCE, verifies the id_token,
/// binds the account to the domain that started the login, provisions or
/// self-heals the principal, syncs IdP roles best-effort, mints the
/// session cookie and lands the user; `GET /auth/oidc/session/end` is
/// RP-initiated logout. Every error is the platform envelope except
/// session-end's, which is the OAuth shape (§4.9).
///
/// Rulings applied: Q3 (`OIDC_VERIFY` is a fixed message, the verifier's
/// reason goes to the log), Q5 (a session-mint failure is the 500
/// envelope), Q22 (the system actor is spelled `system`).
public final class OidcBridgeApi {

    private static final Logger LOG = LoggerFactory.getLogger(OidcBridgeApi.class);

    public static final String CALLBACK_PATH = "/auth/oidc/callback";
    public static final String SYSTEM_ACTOR = "system";
    static final String DEFAULT_LANDING = "/dashboard";

    /// The portal sink (§5.6), wired by the portal unit; until then a
    /// portal-flagged state answers `PORTAL_DISABLED`.
    public interface PortalSink {
        void complete(Context ctx, LoginState state, IdTokenClaims claims);

        static PortalSink disabled() {
            return (ctx, _, _) -> HttpError.write(ctx, 500, "PORTAL_DISABLED", "portal login is not configured", Map.of());
        }
    }

    /// @param externalBaseUrl `FC_JWT_ISSUER`: the absolute origin the callback is registered under; empty ⇒ derived from the request
    public record State(OidcClients clients, LoginStateRepository states, PrincipalRepository principals,
                        EmailDomainMappingRepository mappings, IdentityProviderRepository identityProviders,
                        IdpRoleMappingRepository roleMappings, RoleRepository roles, OAuthClientRepository oauthClients,
                        UnitOfWork uow, TokenIssuer issuer, SessionCookie cookie, PortalSink portal, String externalBaseUrl,
                        Clock clock) {
        public State {
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(states, "states");
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(mappings, "mappings");
            Objects.requireNonNull(identityProviders, "identityProviders");
            Objects.requireNonNull(roleMappings, "roleMappings");
            Objects.requireNonNull(roles, "roles");
            Objects.requireNonNull(oauthClients, "oauthClients");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(cookie, "cookie");
            Objects.requireNonNull(portal, "portal");
            Objects.requireNonNull(clock, "clock");
            externalBaseUrl = externalBaseUrl == null ? "" : externalBaseUrl;
        }
    }

    private OidcBridgeApi() {
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/auth/oidc/login", ctx -> login(ctx, s));
        routes.get(CALLBACK_PATH, ctx -> callback(ctx, s));
        routes.get("/auth/oidc/session/end", ctx -> sessionEnd(ctx, s));
    }

    // ── /auth/oidc/login ───────────────────────────────────────────────────

    static void login(Context ctx, State s) {
        String providerId = q(ctx, "provider_id");
        String domain = q(ctx, "domain");
        if (domain.isEmpty()) {
            String email = q(ctx, "email"); // legacy: the domain is derived from it
            domain = OidcClients.domainOf(email);
        }
        String returnUrl = q(ctx, "return_url");
        if (returnUrl.isEmpty()) {
            returnUrl = q(ctx, "returnUrl");
        }
        if (domain.isEmpty() && providerId.isEmpty()) {
            HttpError.write(ctx, 400, "DOMAIN_REQUIRED", "domain or provider_id query param is required", Map.of());
            return;
        }
        var chain = new LoginState.OAuthChain(q(ctx, "oauth_client_id"), q(ctx, "oauth_redirect_uri"), q(ctx, "oauth_scope"),
                q(ctx, "oauth_state"), q(ctx, "oauth_code_challenge"), q(ctx, "oauth_code_challenge_method"), q(ctx, "oauth_nonce"));

        OidcProvider provider;
        LoginState state;
        if (!providerId.isEmpty()) {
            // provider_id wins over domain when both are present.
            OidcClients.Resolution r;
            try {
                r = s.clients().resolveByProviderId(providerId);
            } catch (OidcClients.ResolutionException e) {
                LOG.warn("oidc resolve by provider failed provider_id={}: {}", providerId, e.getMessage());
                HttpError.write(ctx, 500, "OIDC_RESOLVE_FAILED", "OIDC could not be initialised for this provider", Map.of());
                return;
            }
            provider = r.provider().orElseThrow();
            state = LoginState.begin(domain, r.identityProvider().id(), "", returnUrl, chain, null, s.clock().instant());
        } else {
            OidcClients.Resolution r;
            try {
                r = s.clients().resolveForEmail("x@" + domain);
            } catch (OidcClients.ResolutionException e) {
                LOG.warn("oidc resolve by domain failed domain={}: {}", domain, e.getMessage());
                HttpError.write(ctx, 500, "OIDC_RESOLVE_FAILED", "OIDC could not be initialised for this domain", Map.of());
                return;
            }
            if (r.provider().isEmpty()) {
                HttpError.write(ctx, 400, "OIDC_NOT_CONFIGURED", "OIDC is not configured for this domain", Map.of());
                return;
            }
            provider = r.provider().get();
            state = LoginState.begin(domain, r.identityProvider().id(), r.mapping().id(), returnUrl, chain, null, s.clock().instant());
        }
        try {
            s.states().insert(state);
        } catch (RuntimeException e) {
            LOG.error("oidc login state insert failed", e);
            HttpError.write(ctx, 500, "OIDC_STATE", "persist state failed", Map.of());
            return;
        }
        ctx.redirect(provider.authorizeUrl(callbackUrl(ctx, s), state), HttpStatus.FOUND);
    }

    // ── /auth/oidc/callback ────────────────────────────────────────────────

    static void callback(Context ctx, State s) {
        String stateParam = q(ctx, "state");
        String code = q(ctx, "code");
        if (stateParam.isEmpty() || code.isEmpty()) {
            HttpError.write(ctx, 400, "MISSING_PARAM", "state and code are required", Map.of());
            return;
        }
        Optional<LoginState> consumed;
        try {
            consumed = s.states().consume(stateParam);
        } catch (RuntimeException e) {
            LOG.error("oidc login state lookup failed", e);
            HttpError.write(ctx, 500, "OIDC_STATE", "lookup state failed", Map.of());
            return;
        }
        if (consumed.isEmpty()) {
            HttpError.write(ctx, 400, "INVALID_STATE", "unknown or expired login session", Map.of());
            return;
        }
        // From here the state is burned: any failure means restarting.
        LoginState state = consumed.get();

        OidcProvider provider;
        IdentityProvider idp;
        EmailDomainMapping mapping = null;
        if (state.providerDirect()) {
            OidcClients.Resolution r;
            try {
                r = s.clients().resolveByProviderId(state.identityProviderId());
            } catch (OidcClients.ResolutionException e) {
                LOG.warn("oidc re-resolve by provider failed provider_id={}: {}", state.identityProviderId(), e.getMessage());
                HttpError.write(ctx, 500, "OIDC_RESOLVE_FAILED", "OIDC could not be initialised for this provider", Map.of());
                return;
            }
            provider = r.provider().orElseThrow();
            idp = r.identityProvider();
        } else {
            OidcClients.Resolution r;
            try {
                r = s.clients().resolveForEmail("x@" + state.emailDomain());
            } catch (OidcClients.ResolutionException e) {
                LOG.warn("oidc re-resolve by domain failed domain={}: {}", state.emailDomain(), e.getMessage());
                HttpError.write(ctx, 500, "OIDC_RESOLVE_FAILED", "OIDC could not be initialised for this domain", Map.of());
                return;
            }
            if (r.provider().isEmpty()) {
                HttpError.write(ctx, 400, "OIDC_NOT_CONFIGURED", "OIDC is not configured for this domain", Map.of());
                return;
            }
            provider = r.provider().get();
            idp = r.identityProvider();
            mapping = r.mapping();
        }

        Optional<String> idToken;
        try {
            idToken = provider.exchange(code, state.codeVerifier(), callbackUrl(ctx, s));
        } catch (OidcProvider.ExchangeException e) {
            LOG.warn("oidc code exchange failed issuer={}: {}", provider.config().issuerUrl(), e.getMessage());
            HttpError.write(ctx, 500, "OIDC_EXCHANGE", "code exchange failed", Map.of());
            return;
        }
        if (idToken.isEmpty()) {
            HttpError.write(ctx, 400, "NO_ID_TOKEN", "IDP did not return id_token", Map.of());
            return;
        }
        IdTokenClaims claims;
        switch (provider.verifyIdToken(idToken.get())) {
            case OidcProvider.Rejected rej -> {
                // Ruling Q3: the reason is for the log, not the browser.
                LOG.warn("oidc id_token rejected issuer={}: {}", provider.config().issuerUrl(), rej.reason());
                HttpError.write(ctx, 403, "OIDC_VERIFY", "id_token verification failed", Map.of());
                return;
            }
            case OidcProvider.Verified v -> claims = v.claims();
        }
        if (claims.nonce() == null || !claims.nonce().equals(state.nonce())) {
            HttpError.write(ctx, 403, "NONCE_MISMATCH", "nonce did not match", Map.of());
            return;
        }
        String identifier = claims.identifier();
        if (identifier.isEmpty()) {
            HttpError.write(ctx, 403, "NO_EMAIL", "id_token has no email / preferred_username claim", Map.of());
            return;
        }
        String email = identifier.trim().toLowerCase(Locale.ROOT);
        if (email.contains("#ext#")) {
            HttpError.write(ctx, 403, "EXTERNAL_GUEST", "external guest accounts are not supported", Map.of());
            return;
        }
        String emailDomain = claims.domain();
        if (state.providerDirect()) {
            List<String> allowed = idp.allowedEmailDomains();
            if (!allowed.isEmpty() && allowed.stream().noneMatch(d -> d.equalsIgnoreCase(emailDomain))) {
                HttpError.write(ctx, 403, "EMAIL_DOMAIN_MISMATCH", "the token's email domain is not allowed for this identity provider", Map.of());
                return;
            }
        } else {
            if (!emailDomain.equalsIgnoreCase(state.emailDomain())) {
                HttpError.write(ctx, 403, "EMAIL_DOMAIN_MISMATCH", "the token's email domain does not match the login domain", Map.of());
                return;
            }
            String requiredTenant = mapping.requiredOidcTenantId();
            if (requiredTenant != null && !requiredTenant.isEmpty()) {
                if (claims.tenantId() == null || claims.tenantId().isEmpty()) {
                    HttpError.write(ctx, 403, "TENANT_MISMATCH", "id_token has no tenant id (tid) claim", Map.of());
                    return;
                }
                if (!claims.tenantId().equals(requiredTenant)) {
                    HttpError.write(ctx, 403, "TENANT_MISMATCH", "id_token tenant does not match the configured tenant", Map.of());
                    return;
                }
            }
        }
        if (state.portal()) {
            s.portal().complete(ctx, state, claims);
            return;
        }

        Optional<Principal> existing;
        try {
            existing = s.principals().findByEmail(email);
        } catch (RuntimeException e) {
            LOG.error("principal lookup failed email={}", email, e);
            HttpError.write(ctx, 500, "REPO", "principal lookup failed", Map.of());
            return;
        }
        Principal principal;
        if (existing.isEmpty()) {
            try {
                principal = state.providerDirect() ? provisionPortalUser(s, email) : provision(s, state, email);
            } catch (UseCaseException e) {
                HttpError.write(ctx, e.error());
                return;
            } catch (ProvisioningException e) {
                HttpError.write(ctx, e.status, e.code, e.getMessage(), Map.of());
                return;
            }
        } else {
            principal = existing.get();
            selfHealEmailCase(s, principal, email);
        }
        if (!state.providerDirect()) {
            syncIdpRoles(s, principal, idp.id(), claims.roles());
        }

        String token;
        try {
            token = s.issuer().sessionToken(principal.id(), principal.email());
        } catch (RuntimeException e) {
            // Ruling Q5: the envelope, a fixed message, the cause in the log.
            LOG.error("session token mint failed for principal {}", principal.id(), e);
            HttpError.write(ctx, 500, "SESSION_MINT_FAILED", "session mint failed", Map.of());
            return;
        }
        s.cookie().set(ctx, token);
        ctx.redirect(landing(state), HttpStatus.FOUND);
    }

    /// The employee-plane JIT (§4.7): the mapping that drove the login is
    /// re-fetched by the id stored in the state, and the user is created
    /// in its scope and client by the system actor. No roles here.
    static Principal provision(State s, LoginState state, String email) throws ProvisioningException {
        Optional<EmailDomainMapping> mapping;
        try {
            mapping = s.mappings().findById(state.emailDomainMappingId());
        } catch (RuntimeException e) {
            LOG.error("email_domain_mapping lookup failed id={}", state.emailDomainMappingId(), e);
            throw new ProvisioningException(500, "REPO", "email_domain_mapping lookup failed");
        }
        if (mapping.isEmpty()) {
            throw new ProvisioningException(403, "MAPPING_GONE",
                    "The email-domain mapping that drove this login no longer exists; cannot auto-provision");
        }
        EmailDomainMapping m = mapping.get();
        var cmd = new CreateCommand(email, null, m.scopeType().name(), m.primaryClientId(), null, "OIDC");
        var created = CreateUser.of(s.principals()).run(s.uow(), cmd, ExecutionContext.of(SYSTEM_ACTOR));
        return reread(s, created.userId());
    }

    /// Provider-direct without a portal flow (§4.7): an inert USER principal.
    static Principal provisionPortalUser(State s, String email) throws ProvisioningException {
        var created = CreatePortalUser.of(s.principals()).run(s.uow(), new CreatePortalUserCommand(email, null, "OIDC"),
                ExecutionContext.of(SYSTEM_ACTOR));
        return reread(s, created.userId());
    }

    private static Principal reread(State s, String id) throws ProvisioningException {
        Optional<Principal> p;
        try {
            p = s.principals().findById(id);
        } catch (RuntimeException e) {
            LOG.error("post-create principal lookup failed id={}", id, e);
            throw new ProvisioningException(500, "REPO", "post-create principal lookup failed");
        }
        return p.orElseThrow(() -> new ProvisioningException(500, "REPO", "post-create principal missing"));
    }

    static final class ProvisioningException extends Exception {
        final int status;
        final String code;

        ProvisioningException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /// A principal stored with a differently-cased address is normalised
    /// on login; a failure is logged only.
    private static void selfHealEmailCase(State s, Principal p, String email) {
        if (p.email() == null || p.email().equals(email)) {
            return;
        }
        try {
            s.uow().inTransaction(tx -> {
                s.principals().persist(p.update(new Principal.Changes(null, null, email)), tx.dbTx());
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("lower-casing principal email failed id={}", p.id(), e);
        }
    }

    /// §4.8: mapping path only, after authentication, best-effort. The
    /// provider is re-read; missing ⇒ the empty set (every IDP_SYNC role
    /// dropped); `syncRolesFromIdp` off ⇒ untouched. Claim roles pass
    /// through `oauth_idp_role_mappings` (last duplicate wins) and, when
    /// the provider carries an allow-list, through it too — a list whose
    /// ids all dangle still rejects everything.
    static void syncIdpRoles(State s, Principal p, String identityProviderId, List<String> claimRoles) {
        try {
            Optional<IdentityProvider> idp = s.identityProviders().findById(identityProviderId);
            List<String> platformRoles;
            if (idp.isEmpty()) {
                platformRoles = List.of();
            } else {
                if (!idp.get().syncRolesFromIdp()) {
                    return;
                }
                platformRoles = mapClaimRoles(s, idp.get(), p.id(), claimRoles);
            }
            SyncIdpRoles.of(s.principals(), s.roles()).run(s.uow(), new SyncIdpRolesCommand(p.id(), platformRoles),
                    ExecutionContext.of(SYSTEM_ACTOR));
        } catch (RuntimeException e) {
            LOG.warn("idp role sync failed principal={}", p.id(), e);
        }
    }

    static List<String> mapClaimRoles(State s, IdentityProvider idp, String principalId, List<String> claimRoles) {
        Map<String, String> byIdpRole = new HashMap<>();
        for (IdpRoleMapping m : s.roleMappings().findAll()) {
            byIdpRole.put(m.idpRoleName(), m.platformRoleName());
        }
        boolean hasAllowList = !idp.allowedRoleIds().isEmpty();
        Set<String> allowed = new HashSet<>();
        for (String roleId : idp.allowedRoleIds()) {
            s.roles().findById(roleId).ifPresentOrElse(r -> allowed.add(r.name()),
                    () -> LOG.warn("idp allowed role id {} does not exist; skipped", roleId));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String claim : claimRoles) {
            String platform = byIdpRole.get(claim);
            if (platform == null) {
                LOG.warn("REJECTED unauthorized IDP role: not found in idp_role_mappings principalId={} idpRole={}", principalId, claim);
                continue;
            }
            if (hasAllowList && !allowed.contains(platform)) {
                LOG.debug("idp role {} maps to {} which is not on the provider's allow-list; skipped", claim, platform);
                continue;
            }
            out.add(platform);
        }
        return new ArrayList<>(out);
    }

    /// §4.6: a chained `/oauth/authorize`, else a safe relative `return_url`,
    /// else the dashboard.
    static String landing(LoginState state) {
        LoginState.OAuthChain o = state.oauth();
        if (o != null && o.present()) {
            var b = new StringBuilder("/oauth/authorize?response_type=code&client_id=").append(enc(o.clientId()));
            append(b, "redirect_uri", o.redirectUri());
            append(b, "scope", o.scope());
            append(b, "state", o.state());
            append(b, "code_challenge", o.codeChallenge());
            append(b, "code_challenge_method", o.codeChallengeMethod());
            append(b, "nonce", o.nonce());
            return b.toString();
        }
        String r = state.returnUrl();
        if (r != null && r.startsWith("/") && !r.startsWith("//") && !r.startsWith("/\\")) {
            return r;
        }
        return DEFAULT_LANDING;
    }

    // ── /auth/oidc/session/end ─────────────────────────────────────────────

    static void sessionEnd(Context ctx, State s) {
        s.cookie().clear(ctx);
        String postLogout = q(ctx, "post_logout_redirect_uri");
        if (postLogout.isEmpty()) {
            ctx.status(200).json(Map.of("message", "Session ended"));
            return;
        }
        String clientId = audienceOf(q(ctx, "id_token_hint")).orElse(q(ctx, "client_id"));
        if (clientId.isEmpty()) {
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: id_token_hint or client_id is required to verify post_logout_redirect_uri").write(ctx);
            return;
        }
        Optional<OAuthClient> client;
        try {
            client = s.oauthClients().findByClientId(clientId);
        } catch (RuntimeException e) {
            LOG.error("oauth client lookup failed client_id={}", clientId, e);
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: internal error verifying client").write(ctx);
            return;
        }
        if (client.isEmpty()) {
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: id_token_hint audience does not match any registered client").write(ctx);
            return;
        }
        if (!RedirectUriMatcher.matches(postLogout, client.get().postLogoutRedirectUris())) {
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: not in the client's registered post_logout_redirect_uris").write(ctx);
            return;
        }
        String state = q(ctx, "state");
        String target = postLogout;
        if (!state.isEmpty()) {
            target += (postLogout.contains("?") ? "&" : "?") + "state=" + enc(state);
        }
        ctx.redirect(target, HttpStatus.SEE_OTHER);
    }

    /// The `aud` of an id_token hint, read from the payload **without** a
    /// signature check (the client is then looked up and the URI matched
    /// against its registered list, which is the actual authority).
    static Optional<String> audienceOf(String idTokenHint) {
        if (idTokenHint == null || idTokenHint.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = idTokenHint.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            var payload = io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            var aud = payload.get("aud");
            if (aud == null) {
                return Optional.empty();
            }
            if (aud.isString()) {
                return aud.asString().isEmpty() ? Optional.empty() : Optional.of(aud.asString());
            }
            if (aud.isArray() && !aud.isEmpty() && aud.get(0).isString()) {
                return Optional.of(aud.get(0).asString());
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /// §4.5: the external base URL trimmed of `/` + the callback path;
    /// when unset, derived from the forwarding headers (development only).
    public static String callbackUrl(Context ctx, State s) {
        String base = s.externalBaseUrl();
        if (base.isEmpty()) {
            String proto = header(ctx, "X-Forwarded-Proto");
            if (proto.isEmpty()) {
                proto = ctx.scheme();
            }
            String host = header(ctx, "X-Forwarded-Host");
            if (host.isEmpty()) {
                host = header(ctx, "Host");
            }
            base = proto + "://" + host;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + CALLBACK_PATH;
    }

    private static String header(Context ctx, String name) {
        String v = ctx.header(name);
        return v == null ? "" : v.trim();
    }

    private static void append(StringBuilder b, String key, String value) {
        if (value != null && !value.isEmpty()) {
            b.append('&').append(key).append('=').append(enc(value));
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String q(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null ? "" : v.trim();
    }

    static boolean isOidc(IdentityProvider idp) {
        return idp.type() == IdentityProviderType.OIDC;
    }
}

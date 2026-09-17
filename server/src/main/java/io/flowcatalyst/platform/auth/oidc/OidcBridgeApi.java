package io.flowcatalyst.platform.auth.oidc;

import io.flowcatalyst.platform.auth.login.ClientIp;
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
import io.flowcatalyst.platform.loginattempt.AttemptOutcome;
import io.flowcatalyst.platform.loginattempt.AttemptType;
import io.flowcatalyst.platform.loginattempt.LoginAttempt;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.principal.operations.CreateCommand;
import io.flowcatalyst.platform.principal.operations.CreatePortalUser;
import io.flowcatalyst.platform.principal.operations.CreatePortalUserCommand;
import io.flowcatalyst.platform.principal.operations.CreateUser;
import io.flowcatalyst.platform.principal.operations.OidcLogin;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.FederatedClaims;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.FlowcatalystClaims;
import io.flowcatalyst.platform.principal.operations.RecordOidcLogin;
import io.flowcatalyst.platform.principal.operations.SyncIdpRoles;
import io.flowcatalyst.platform.principal.operations.SyncIdpRolesCommand;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
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
import java.util.TreeSet;

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
        void complete(Exchange ctx, LoginState state, IdTokenClaims claims);

        static PortalSink disabled() {
            return (ctx, _, _) -> HttpError.write(ctx, 500, "PORTAL_DISABLED", "portal login is not configured", Map.of());
        }
    }

    /// @param externalBaseUrl `FC_JWT_ISSUER`: the absolute origin the callback is registered under; empty ⇒ derived from the request
    /// @param attempts the login-attempt store (spec `docs/spec/sso-login-attempts.md`); `null` disables recording
    public record State(OidcClients clients, LoginStateRepository states, PrincipalRepository principals,
                        EmailDomainMappingRepository mappings, IdentityProviderRepository identityProviders,
                        LoginAttemptRepository attempts, IdpRoleMappingRepository roleMappings, RoleRepository roles,
                        OAuthClientRepository oauthClients, UnitOfWork uow, TokenIssuer issuer, SessionCookie cookie,
                        PortalSink portal, String externalBaseUrl, Clock clock) {
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

    public static void register(Routes routes, State s) {
        routes.get("/auth/oidc/login", ctx -> login(ctx, s));
        routes.get(CALLBACK_PATH, ctx -> callback(ctx, s));
        routes.get("/auth/oidc/session/end", ctx -> sessionEnd(ctx, s));
    }

    // ── /auth/oidc/login ───────────────────────────────────────────────────

    static void login(Exchange ctx, State s) {
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
                LOG.atWarn().setMessage("oidc resolve by provider failed")
                        .addKeyValue("provider_id", providerId)
                        .setCause(e)
                        .log();
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
                LOG.atWarn().setMessage("oidc resolve by domain failed")
                        .addKeyValue("domain", domain)
                        .setCause(e)
                        .log();
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
        ctx.redirect(provider.authorizeUrl(callbackUrl(ctx, s), state), 302);
    }

    // ── /auth/oidc/callback ────────────────────────────────────────────────

    static void callback(Exchange ctx, State s) {
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
                LOG.atWarn().setMessage("oidc re-resolve by provider failed")
                        .addKeyValue("provider_id", state.identityProviderId())
                        .setCause(e)
                        .log();
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
                LOG.atWarn().setMessage("oidc re-resolve by domain failed")
                        .addKeyValue("domain", state.emailDomain())
                        .setCause(e)
                        .log();
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

        OidcProvider.ExchangeResult tokens;
        try {
            tokens = provider.exchange(code, state.codeVerifier(), callbackUrl(ctx, s));
        } catch (OidcProvider.ExchangeException e) {
            LOG.atWarn().setMessage("oidc code exchange failed")
                    .addKeyValue("issuer", provider.config().issuerUrl())
                    .setCause(e)
                    .log();
            HttpError.write(ctx, 500, "OIDC_EXCHANGE", "code exchange failed", Map.of());
            return;
        }
        if (tokens.idToken().isEmpty()) {
            HttpError.write(ctx, 400, "NO_ID_TOKEN", "IDP did not return id_token", Map.of());
            return;
        }
        IdTokenClaims claims;
        switch (provider.verifyIdToken(tokens.idToken().get())) {
            case OidcProvider.Rejected rej -> {
                // Ruling Q3: the reason is for the log, not the browser.
                LOG.atWarn().setMessage("oidc id_token rejected")
                        .addKeyValue("issuer", provider.config().issuerUrl())
                        .addKeyValue("reason", rej.reason())
                        .log();
                HttpError.write(ctx, 403, "OIDC_VERIFY", "id_token verification failed", Map.of());
                record(s, ctx, state, AttemptOutcome.FAILURE, null, null, "SSO: id_token verification failed");
                return;
            }
            case OidcProvider.Verified v -> claims = v.claims();
        }
        if (claims.nonce() == null || !claims.nonce().equals(state.nonce())) {
            HttpError.write(ctx, 403, "NONCE_MISMATCH", "nonce did not match", Map.of());
            record(s, ctx, state, AttemptOutcome.FAILURE, verifiedIdentifier(claims), null, "SSO: nonce mismatch");
            return;
        }
        String identifier = claims.identifier();
        if (identifier.isEmpty()) {
            HttpError.write(ctx, 403, "NO_EMAIL", "id_token has no email / preferred_username claim", Map.of());
            record(s, ctx, state, AttemptOutcome.FAILURE, null, null, "SSO: no email claim");
            return;
        }
        String email = identifier.trim().toLowerCase(Locale.ROOT);
        if (email.contains("#ext#")) {
            HttpError.write(ctx, 403, "EXTERNAL_GUEST", "external guest accounts are not supported", Map.of());
            record(s, ctx, state, AttemptOutcome.FAILURE, email, null, "SSO: external guest account");
            return;
        }
        String emailDomain = claims.domain();
        if (state.providerDirect()) {
            List<String> allowed = idp.allowedEmailDomains();
            if (!allowed.isEmpty() && allowed.stream().noneMatch(d -> d.equalsIgnoreCase(emailDomain))) {
                HttpError.write(ctx, 403, "EMAIL_DOMAIN_MISMATCH", "the token's email domain is not allowed for this identity provider", Map.of());
                record(s, ctx, state, AttemptOutcome.FAILURE, email, null, "SSO: email domain not allowed");
                return;
            }
        } else {
            if (!emailDomain.equalsIgnoreCase(state.emailDomain())) {
                HttpError.write(ctx, 403, "EMAIL_DOMAIN_MISMATCH", "the token's email domain does not match the login domain", Map.of());
                record(s, ctx, state, AttemptOutcome.FAILURE, email, null, "SSO: email domain not allowed");
                return;
            }
            String requiredTenant = mapping.requiredOidcTenantId();
            if (requiredTenant != null && !requiredTenant.isEmpty()) {
                if (claims.tenantId() == null || claims.tenantId().isEmpty()) {
                    HttpError.write(ctx, 403, "TENANT_MISMATCH", "id_token has no tenant id (tid) claim", Map.of());
                    record(s, ctx, state, AttemptOutcome.FAILURE, email, null, "SSO: tenant mismatch");
                    return;
                }
                if (!claims.tenantId().equals(requiredTenant)) {
                    HttpError.write(ctx, 403, "TENANT_MISMATCH", "id_token tenant does not match the configured tenant", Map.of());
                    record(s, ctx, state, AttemptOutcome.FAILURE, email, null, "SSO: tenant mismatch");
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
            LOG.atError().setMessage("principal lookup failed")
                    .addKeyValue("email", email)
                    .setCause(e)
                    .log();
            HttpError.write(ctx, 500, "REPO", "principal lookup failed", Map.of());
            return;
        }
        Principal principal;
        if (existing.isEmpty()) {
            try {
                principal = state.providerDirect() ? provisionPortalUser(s, email) : provision(s, state, email);
            } catch (ProvisioningException e) {
                // Spec: only a refusal (4xx) is an identity being refused; a 500 (e.g. the
                // email_domain_mapping lookup failing) is infrastructure noise, not recorded.
                if (e.status >= 400 && e.status < 500) {
                    record(s, ctx, state, AttemptOutcome.FAILURE, email, null, "SSO: account provisioning refused");
                }
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
            LOG.atError().setMessage("session token mint failed")
                    .addKeyValue("principal", principal.id())
                    .setCause(e)
                    .log();
            HttpError.write(ctx, 500, "SESSION_MINT_FAILED", "session mint failed", Map.of());
            return;
        }
        // spec docs/spec/oidc-logged-in-event.md: after the mint succeeds, before the
        // redirect — a login that actually succeeded — and best-effort: a failure here
        // must never change the login's outcome.
        emitLoggedIn(s, principal, email, idp.id(), idp.code(), claims, tokens.accessToken().orElse(null));
        record(s, ctx, state, AttemptOutcome.SUCCESS, email, principal.id(), null);
        s.cookie().set(ctx, token);
        ctx.redirect(landing(state), 302);
    }

    /// Spec `docs/spec/sso-login-attempts.md`: the callback's accept/refuse
    /// outcomes, employee plane only — a portal-flow state (`state.portal()`)
    /// writes no row at all, success or failure, checked once here rather
    /// than at every call site. Best-effort: a failed write is logged at
    /// WARN and never changes the response.
    private static void record(State s, Exchange ctx, LoginState state, AttemptOutcome outcome, String identifier,
                               String principalId, String failureReason) {
        if (s.attempts() == null || state.portal()) {
            return;
        }
        try {
            s.attempts().recordAttempt(LoginAttempt.attempt(AttemptType.USER_LOGIN, outcome, failureReason, identifier,
                    principalId, blankToNull(ClientIp.of(ctx)), blankToNull(header(ctx, "User-Agent"))));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("recording SSO login attempt failed")
                    .addKeyValue("outcome", outcome)
                    .setCause(e)
                    .log();
        }
    }

    /// The verified id_token's normalised identifier (spec table): `null`
    /// when [IdTokenClaims#identifier] is empty. Called only where `claims`
    /// has already passed signature/audience/issuer verification — never a
    /// claim read from an unverified token.
    private static String verifiedIdentifier(IdTokenClaims claims) {
        String id = claims.identifier();
        return id.isEmpty() ? null : id.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String v) {
        return v.isBlank() ? null : v;
    }

    /// Emits [io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserLoggedIn]
    /// (spec `docs/spec/oidc-logged-in-event.md`) — OIDC logins only, never
    /// the portal flow (which returns earlier) or a failed one (every error
    /// branch above already returned). The principal is re-read so
    /// `flowcatalystClaims.roles` reflects this login's IdP role sync, not
    /// the copy loaded before it. Best-effort: logged and swallowed.
    private static void emitLoggedIn(State s, Principal loggedIn, String email, String identityProviderId,
                                     String identityProviderCode, IdTokenClaims claims, String accessToken) {
        try {
            Principal fresh = s.principals().findById(loggedIn.id()).orElse(loggedIn);
            List<String> roles = fresh.roleNames();
            List<String> clients = fresh.scope() == UserScope.ANCHOR ? List.of(Principal.ANCHOR_CLIENT_WILDCARD) : fresh.assignedClients();
            var flowcatalystClaims = new FlowcatalystClaims(email, "USER", roles, clients, applicationPrefixesOf(roles));
            var federatedClaims = new FederatedClaims(claims.rawClaims(), OidcProvider.decodeUnverifiedPayload(accessToken));
            RecordOidcLogin.of(fresh.id(), identityProviderCode, flowcatalystClaims, federatedClaims)
                    .run(s.uow(), new OidcLogin(email, identityProviderId), ExecutionContext.of(fresh.id()));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("failed to emit UserLoggedIn event (login still succeeded)")
                    .addKeyValue("principal", loggedIn.id())
                    .setCause(e)
                    .log();
        }
    }

    /// The distinct, sorted `app` prefixes of every `app:role` name (spec
    /// `docs/spec/oidc-logged-in-event.md`); a role with no `:` contributes
    /// nothing.
    static List<String> applicationPrefixesOf(List<String> roles) {
        var prefixes = new TreeSet<String>();
        for (String role : roles) {
            int i = role.indexOf(':');
            if (i > 0) {
                prefixes.add(role.substring(0, i));
            }
        }
        return List.copyOf(prefixes);
    }

    /// The employee-plane JIT (§4.7): the mapping that drove the login is
    /// re-fetched by the id stored in the state, and the user is created
    /// in its scope and client by the system actor. No roles here.
    static Principal provision(State s, LoginState state, String email) throws ProvisioningException {
        Optional<EmailDomainMapping> mapping;
        try {
            mapping = s.mappings().findById(state.emailDomainMappingId());
        } catch (RuntimeException e) {
            LOG.atError().setMessage("email_domain_mapping lookup failed")
                    .addKeyValue("id", state.emailDomainMappingId())
                    .setCause(e)
                    .log();
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
            LOG.atError().setMessage("post-create principal lookup failed")
                    .addKeyValue("id", id)
                    .setCause(e)
                    .log();
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
            LOG.atWarn().setMessage("lower-casing principal email failed")
                    .addKeyValue("id", p.id())
                    .setCause(e)
                    .log();
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
            LOG.atWarn().setMessage("idp role sync failed")
                    .addKeyValue("principal", p.id())
                    .setCause(e)
                    .log();
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
                    () -> LOG.atWarn().setMessage("idp allowed role does not exist; skipped")
                            .addKeyValue("role_id", roleId)
                            .log());
        }
        Set<String> out = new LinkedHashSet<>();
        for (String claim : claimRoles) {
            String platform = byIdpRole.get(claim);
            if (platform == null) {
                LOG.atWarn().setMessage("REJECTED unauthorized IDP role: not found in idp_role_mappings")
                        .addKeyValue("principal", principalId)
                        .addKeyValue("idp_role", claim)
                        .log();
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

    static void sessionEnd(Exchange ctx, State s) {
        s.cookie().clear(ctx);
        String postLogout = q(ctx, "post_logout_redirect_uri");
        if (postLogout.isEmpty()) {
            ctx.status(200).json(Map.of("message", "Session ended"));
            return;
        }
        String clientId = audienceOf(q(ctx, "id_token_hint")).orElse(q(ctx, "client_id"));
        if (clientId.isEmpty()) {
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: id_token_hint or client_id is required to verify post_logout_redirect_uri").writePlain(ctx);
            return;
        }
        Optional<OAuthClient> client;
        try {
            client = s.oauthClients().findByClientId(clientId);
        } catch (RuntimeException e) {
            LOG.atError().setMessage("oauth client lookup failed")
                    .addKeyValue("oauth_client_id", clientId)
                    .setCause(e)
                    .log();
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: internal error verifying client").writePlain(ctx);
            return;
        }
        if (client.isEmpty()) {
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: id_token_hint audience does not match any registered client").writePlain(ctx);
            return;
        }
        if (!RedirectUriMatcher.matches(postLogout, client.get().postLogoutRedirectUris())) {
            OAuthError.invalidRequest("Invalid post_logout_redirect_uri: not in the client's registered post_logout_redirect_uris").writePlain(ctx);
            return;
        }
        String state = q(ctx, "state");
        String target = postLogout;
        if (!state.isEmpty()) {
            target += (postLogout.contains("?") ? "&" : "?") + "state=" + enc(state);
        }
        ctx.redirect(target, 303);
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
    public static String callbackUrl(Exchange ctx, State s) {
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

    private static String header(Exchange ctx, String name) {
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

    private static String q(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null ? "" : v.trim();
    }

    static boolean isOidc(IdentityProvider idp) {
        return idp.type() == IdentityProviderType.OIDC;
    }
}

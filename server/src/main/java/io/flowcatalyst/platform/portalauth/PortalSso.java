package io.flowcatalyst.platform.portalauth;

import io.flowcatalyst.platform.auth.grant.AuthorizationCode;
import io.flowcatalyst.platform.auth.grant.GrantStore;
import io.flowcatalyst.platform.auth.oidc.IdTokenClaims;
import io.flowcatalyst.platform.auth.oidc.LoginState;
import io.flowcatalyst.platform.auth.oidc.LoginStateRepository;
import io.flowcatalyst.platform.auth.oidc.OidcBridgeApi;
import io.flowcatalyst.platform.auth.oidc.OidcClients;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentityStatus;
import io.flowcatalyst.platform.portalidentity.operations.EnsureCommand;
import io.flowcatalyst.platform.portalidentity.operations.EnsurePortalIdentity;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Portal SSO (`docs/spec/auth-identity.md` §5.6 with ruling Q10): the
/// start at `GET /portal/auth/oidc/login?flow=&provider_id=` consumes the
/// parked flow (single use — a failed IdP round-trip means restarting
/// from the portal) and hands the chain to the bridge in a portal-flagged
/// state; the sink runs when the bridge's callback has verified the
/// id_token and bound it to the provider's domains: JIT the identity as
/// the `system` actor, refuse a DISABLED one with `access_denied`, issue
/// the authorization code with the `ptu_` subject, and 302 — never an
/// `fc_session`.
public final class PortalSso implements OidcBridgeApi.PortalSink {

    private static final Logger LOG = LoggerFactory.getLogger(PortalSso.class);

    public record State(PortalLoginFlowRepository flows, PortalIdentityRepository identities, ClientRepository clients,
                        UnitOfWork uow, GrantStore grants, OidcClients oidcClients, LoginStateRepository states,
                        OidcBridgeApi.State bridge, Clock clock) {
        public State {
            Objects.requireNonNull(flows, "flows");
            Objects.requireNonNull(identities, "identities");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(grants, "grants");
            Objects.requireNonNull(oidcClients, "oidcClients");
            Objects.requireNonNull(states, "states");
            Objects.requireNonNull(clock, "clock");
        }
    }

    private final State s;

    public PortalSso(State s) {
        this.s = Objects.requireNonNull(s, "state");
    }

    public void register(Routes routes) {
        routes.get("/portal/auth/oidc/login", this::start);
    }

    // ── start ──────────────────────────────────────────────────────────────

    void start(Exchange ctx) {
        String flowId = q(ctx, "flow");
        String providerId = q(ctx, "provider_id");
        if (flowId.isEmpty() || providerId.isEmpty()) {
            HttpError.write(ctx, 400, "MISSING_PARAM", "flow and provider_id are required", Map.of());
            return;
        }
        Optional<PortalLoginFlow> flow;
        try {
            flow = s.flows().consume(flowId);
        } catch (RuntimeException e) {
            LOG.error("portal login flow consume failed", e);
            HttpError.write(ctx, 500, "FLOW", "login flow lookup failed", Map.of());
            return;
        }
        if (flow.isEmpty()) {
            HttpError.write(ctx, 400, "FLOW_EXPIRED", "The login flow has expired — return to the portal and try again", Map.of());
            return;
        }
        OidcClients.Resolution r;
        try {
            r = s.oidcClients().resolveByProviderId(providerId);
        } catch (OidcClients.ResolutionException e) {
            LOG.warn("portal sso resolve by provider failed provider_id={}: {}", providerId, e.getMessage());
            HttpError.write(ctx, 500, "OIDC_RESOLVE_FAILED", "OIDC could not be initialised for this provider", Map.of());
            return;
        }
        PortalLoginFlow f = flow.get();
        var chain = new LoginState.OAuthChain(f.oauthClientId(), f.redirectUri(), f.scope(), f.state(), f.codeChallenge(),
                f.codeChallengeMethod(), f.nonce());
        LoginState state = LoginState.begin("", r.identityProvider().id(), "", null, chain, f.portalClientId(), s.clock().instant());
        try {
            s.states().insert(state);
        } catch (RuntimeException e) {
            LOG.error("oidc login state insert failed", e);
            HttpError.write(ctx, 500, "OIDC_STATE", "persist state failed", Map.of());
            return;
        }
        ctx.redirect(r.provider().orElseThrow().authorizeUrl(OidcBridgeApi.callbackUrl(ctx, s.bridge()), state), 302);
    }

    // ── sink ───────────────────────────────────────────────────────────────

    @Override
    public void complete(Exchange ctx, LoginState state, IdTokenClaims claims) {
        LoginState.OAuthChain o = state.oauth();
        if (o == null || !o.present() || o.redirectUri() == null || o.redirectUri().isEmpty() || o.state() == null || o.state().isEmpty()) {
            HttpError.write(ctx, 400, "PORTAL_STATE_INVALID", "portal login state is missing its OAuth chain", Map.of());
            return;
        }
        String email = claims.identifier().trim().toLowerCase(java.util.Locale.ROOT);
        Optional<PortalIdentity> found;
        try {
            found = s.identities().findByClientAndEmail(state.portalClientId(), email);
        } catch (RuntimeException e) {
            LOG.error("portal identity lookup failed", e);
            HttpError.write(ctx, 500, "IDENTITY", "identity lookup failed", Map.of());
            return;
        }
        PortalIdentity identity;
        if (found.isEmpty()) {
            String name = claims.name() == null || claims.name().isBlank() ? null : claims.name().trim();
            try {
                var event = EnsurePortalIdentity.of(s.identities(), s.clients()).run(s.uow(),
                        new EnsureCommand(state.portalClientId(), email, name, "JIT"), ExecutionContext.of(OidcBridgeApi.SYSTEM_ACTOR));
                identity = s.identities().findById(event.identityId()).orElse(null);
            } catch (UseCaseException e) {
                HttpError.write(ctx, e.error());
                return;
            }
            if (identity == null) {
                HttpError.write(ctx, 500, "IDENTITY", "post-create identity lookup failed", Map.of());
                return;
            }
        } else {
            identity = found.get();
        }
        if (identity.status() == PortalIdentityStatus.DISABLED) {
            // SSO never self-reactivates a suspended account.
            ctx.redirect(o.redirectUri() + (o.redirectUri().contains("?") ? "&" : "?")
                    + "error=access_denied&error_description=" + enc("This account is suspended for this portal")
                    + "&state=" + enc(o.state()), 302);
            return;
        }
        AuthorizationCode code = AuthorizationCode.issue(o.clientId(), identity.id(), o.redirectUri(), s.clock().instant())
                .withScope(o.scope()).withNonce(o.nonce()).withState(o.state())
                .withPkce(o.codeChallenge(), o.codeChallenge() == null ? null : (o.codeChallengeMethod() == null ? "S256" : o.codeChallengeMethod()));
        try {
            s.grants().insert(code);
        } catch (RuntimeException e) {
            LOG.error("portal authorization code insert failed", e);
            HttpError.write(ctx, 500, "CODE", "could not issue the authorization code", Map.of());
            return;
        }
        try {
            s.identities().touchLastLogin(identity.id());
        } catch (RuntimeException e) {
            LOG.warn("touchLastLogin failed for portal identity {}", identity.id(), e);
        }
        ctx.redirect(o.redirectUri() + (o.redirectUri().contains("?") ? "&" : "?") + "code=" + enc(code.code())
                + "&state=" + enc(o.state()), 302);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String q(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null ? "" : v.trim();
    }
}

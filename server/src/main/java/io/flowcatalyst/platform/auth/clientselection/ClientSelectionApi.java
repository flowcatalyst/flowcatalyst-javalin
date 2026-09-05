package io.flowcatalyst.platform.auth.clientselection;

import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.token.ClaimLabels;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.ClientStatus;
import io.flowcatalyst.platform.principal.ClientAccessGrant;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// `/auth/client/*` (`docs/spec/auth-core.md` §6.5; Go `clientselection`):
/// the tenants a signed-in user may work in, a switch that hands back a
/// fresh full-authority API token for the chosen one, and the current
/// client context. The switch changes nothing stored — the token is the
/// same one `/auth/refresh` would mint; the check is that the user may
/// reach the client at all, and that it is active. All three run inside
/// the authenticator.
public final class ClientSelectionApi {

    public record State(PrincipalRepository principals, ClientRepository clients, ClientAccessGrantRepository grants,
                        TokenIssuer issuer, DbClaimsResolver resolver, ClaimLabels labels) {
        public State {
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(grants, "grants");
            Objects.requireNonNull(issuer, "issuer");
            Objects.requireNonNull(resolver, "resolver");
            Objects.requireNonNull(labels, "labels");
        }
    }

    private ClientSelectionApi() {
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/auth/client/accessible", Auth.scoped(ctx -> accessible(ctx, s)));
        routes.post("/auth/client/switch", Auth.scoped(ctx -> switchClient(ctx, s)));
        routes.get("/auth/client/current", Auth.scoped(ctx -> current(ctx, s)));
    }

    record ClientInfo(String id, String name, String identifier) {
        static ClientInfo of(Client c) {
            return new ClientInfo(c.id(), c.name(), c.identifier());
        }
    }

    record AccessibleResponse(List<ClientInfo> clients, String currentClientId, boolean globalAccess) {
    }

    record SwitchRequest(String clientId) {
    }

    record SwitchResponse(String token, ClientInfo client, List<String> roles, List<String> permissions) {
    }

    record CurrentResponse(ClientInfo client, boolean noClientContext) {
    }

    static void accessible(Context ctx, State s) {
        Principal p = load(s);
        var out = new ArrayList<ClientInfo>();
        for (String id : accessibleClientIds(s, p)) {
            s.clients().findById(id).filter(c -> c.status() == ClientStatus.ACTIVE).ifPresent(c -> out.add(ClientInfo.of(c)));
        }
        out.sort(Comparator.comparing(ClientInfo::name));
        ctx.json(new AccessibleResponse(out, p.clientId(), p.scope() == io.flowcatalyst.platform.principal.UserScope.ANCHOR));
    }

    static void switchClient(Context ctx, State s) {
        SwitchRequest req;
        try {
            req = Json.MAPPER.readValue(ctx.body(), SwitchRequest.class);
        } catch (RuntimeException e) {
            throw UseCaseException.validation("INVALID_JSON", "malformed request body");
        }
        String clientId = req.clientId() == null ? "" : req.clientId();
        Principal p = load(s);
        if (p.scope() != io.flowcatalyst.platform.principal.UserScope.ANCHOR && !accessibleClientIds(s, p).contains(clientId)) {
            throw UseCaseException.authorization("FORBIDDEN", "Access denied to client: " + clientId);
        }
        Client c = s.clients().findById(clientId).orElseThrow(() -> UseCaseException.resourceNotFound("Client", clientId));
        if (c.status() != ClientStatus.ACTIVE) {
            throw UseCaseException.authorization("FORBIDDEN", "Client is not active: " + c.name());
        }
        List<String> ceiling = s.resolver().ceiling(p);
        String token = s.issuer().accessToken(p, TokenIssuer.Authority.full(p, ceiling, s.labels()), null);
        ctx.json(new SwitchResponse(token, ClientInfo.of(c), p.roleNames(), s.resolver().flattenPermissions(p.roleNames())));
    }

    static void current(Context ctx, State s) {
        Principal p = load(s);
        Optional<Client> c = p.clientId() == null ? Optional.empty() : s.clients().findById(p.clientId());
        ctx.json(new CurrentResponse(c.map(ClientInfo::of).orElse(null), c.isEmpty()));
    }

    /// Anchors: every active client; CLIENT scope: the home client; PARTNER:
    /// the assigned clients — the latter two plus every access grant.
    static List<String> accessibleClientIds(State s, Principal p) {
        var ids = new LinkedHashSet<String>();
        switch (p.scope()) {
            case ANCHOR -> s.clients().findAll().stream().filter(c -> c.status() == ClientStatus.ACTIVE).map(Client::id).forEach(ids::add);
            case CLIENT -> {
                if (p.clientId() != null) {
                    ids.add(p.clientId());
                }
                s.grants().findByPrincipal(p.id()).stream().map(ClientAccessGrant::clientId).forEach(ids::add);
            }
            case PARTNER -> {
                ids.addAll(p.assignedClients());
                s.grants().findByPrincipal(p.id()).stream().map(ClientAccessGrant::clientId).forEach(ids::add);
            }
        }
        return new ArrayList<>(ids);
    }

    private static Principal load(State s) {
        Optional<AuthContext> ac = Auth.currentOptional();
        if (ac.isEmpty() || ac.get().principalId() == null || ac.get().principalId().isBlank()) {
            throw UseCaseException.authorization("UNAUTHENTICATED", "authentication required");
        }
        String id = ac.get().principalId();
        return s.principals().findById(id).orElseThrow(() -> UseCaseException.resourceNotFound("Principal", id));
    }
}

package io.flowcatalyst.platform.bff.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// The `/api/me` surface (bff spec §8): the SPA's "who am I" lookup,
/// straight from the authenticated [AuthContext] plus the identity fields
/// only the principal row carries.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/me` | 200 [WhoAmIResponse]; 404 if the principal row no longer exists |
/// | GET | `/api/me/applications` | 200 [ApplicationsResponse] |
/// | GET | `/api/me/clients` | 200 [ClientsResponse] |
/// | GET | `/api/me/clients/{clientId}` | 200 [MyClientResponse]; 404 when inaccessible |
/// | GET | `/api/me/clients/{clientId}/applications` | 200 [ApplicationsResponse]; 404 when inaccessible |
public final class MeApi {

    private MeApi() {
    }

    public record State(PrincipalRepository principals, ApplicationRepository applications, ClientRepository clients,
                        ClientConfigRepository clientConfigs) {
        public State {
            Objects.requireNonNull(principals, "principals");
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(clientConfigs, "clientConfigs");
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/me", Auth.scoped(ctx -> whoami(ctx, s)));
        routes.get("/api/me/applications", Auth.scoped(ctx -> myApplications(ctx, s)));
        routes.get("/api/me/clients", Auth.scoped(ctx -> myClients(ctx, s)));
        routes.get("/api/me/clients/{clientId}", Auth.scoped(ctx -> myClient(ctx, s)));
        routes.get("/api/me/clients/{clientId}/applications", Auth.scoped(ctx -> myClientApplications(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    /// Roles, permissions, clients and applications come straight off the
    /// authenticated [AuthContext] — the token/claims resolver already
    /// computed them (an application-scoped credential's `applications` claim
    /// is already narrowed; re-deriving from the principal row would leak its
    /// full platform-wide access instead). `name`, `email` and `active` are
    /// the identity fields only the principal row carries; a caller whose
    /// principal no longer exists gets 404 (bff spec §8).
    private static void whoami(Context ctx, State s) {
        AuthContext ac = requireAuthenticated(ctx);
        Principal p = s.principals().findById(ac.principalId())
                .orElseThrow(() -> HttpError.notFound("Principal", ac.principalId()));
        ctx.json(new WhoAmIResponse(ac.principalId(), p.type().name(), ac.scope() == null ? null : ac.scope().name(),
                p.name(), p.email(), p.active(), ac.roles(), ac.permissions(), ac.clients(), ac.applications(),
                ac.allApplications()));
    }

    /// Every application (active and inactive), filtered to the caller's
    /// accessible set unless [AuthContext#allApplications()].
    private static void myApplications(Context ctx, State s) {
        AuthContext ac = requireAuthenticated(ctx);
        var apps = accessibleApplications(s, ac.allApplications(), Set.copyOf(ac.applications()));
        ctx.json(new ApplicationsResponse(apps, apps.size(), null));
    }

    private static void myClients(Context ctx, State s) {
        AuthContext ac = requireAuthenticated(ctx);
        var out = s.clients().findAll().stream()
                .filter(c -> ac.isAnchor() || ac.canAccessClient(c.id()))
                .map(MyClientResponse::from)
                .toList();
        ctx.json(new ClientsResponse(out, out.size()));
    }

    private static void myClient(Context ctx, State s) {
        AuthContext ac = requireAuthenticated(ctx);
        Client c = accessibleClient(s, ac, ctx.pathParam("clientId"));
        ctx.json(MyClientResponse.from(c));
    }

    /// The client's enabled application configs, joined to applications.
    private static void myClientApplications(Context ctx, State s) {
        AuthContext ac = requireAuthenticated(ctx);
        Client c = accessibleClient(s, ac, ctx.pathParam("clientId"));
        Set<String> enabled = s.clientConfigs().findByClient(c.id()).stream()
                .filter(ClientConfig::enabled)
                .map(ClientConfig::applicationId)
                .collect(java.util.stream.Collectors.toSet());
        var apps = accessibleApplications(s, false, enabled);
        ctx.json(new ApplicationsResponse(apps, apps.size(), c.id()));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// `null` (unbound / test-header-less) reads as `UNAUTHENTICATED`, same as [Checks].
    private static AuthContext requireAuthenticated(Context ctx) {
        AuthContext ac = Auth.current();
        if (ac == null) throw HttpError.unauthenticated();
        return ac;
    }

    private static Client accessibleClient(State s, AuthContext ac, String clientId) {
        Client c = s.clients().findById(clientId).orElseThrow(() -> HttpError.notFound("Client", clientId));
        if (!ac.isAnchor() && !ac.canAccessClient(c.id())) throw HttpError.notFound("Client", clientId); // bff spec §8: 404, not 403
        return c;
    }

    private static List<ApplicationSummary> accessibleApplications(State s, boolean allApplications, Set<String> accessible) {
        return s.applications().findWithFilters(new ApplicationRepository.ListFilter(null, null)).stream()
                .filter(a -> allApplications || accessible.contains(a.id()))
                .map(ApplicationSummary::from)
                .toList();
    }

    // ── Wire DTOs (bff spec §8) ──────────────────────────────────────────────

    public record WhoAmIResponse(
            String principalId,
            String principalType,
            String scope,
            String name,
            String email,
            boolean active,
            List<String> roles,
            List<String> permissions,
            List<String> accessibleClientIds,
            List<String> accessibleApplicationIds,
            boolean allApplications) {
    }

    public record ApplicationSummary(String id, String code, String name, String description, String iconUrl,
                                     String baseUrl, String website, String logoMimeType) {
        static ApplicationSummary from(Application a) {
            return new ApplicationSummary(a.id(), a.code(), a.name(), a.description(), a.iconUrl(),
                    a.defaultBaseUrl(), a.website(), a.logoMimeType());
        }
    }

    /// `clientId` is `null` (omitted) for the principal-scoped variant, set
    /// for the per-client variant — the two calls share this envelope.
    public record ApplicationsResponse(List<ApplicationSummary> applications, int total, String clientId) {
        public ApplicationsResponse {
            applications = applications == null ? List.of() : List.copyOf(applications);
        }
    }

    public record MyClientResponse(String id, String name, String identifier, String status, Instant createdAt, Instant updatedAt) {
        static MyClientResponse from(Client c) {
            return new MyClientResponse(c.id(), c.name(), c.identifier(), c.status().name(), c.createdAt(), c.updatedAt());
        }
    }

    public record ClientsResponse(List<MyClientResponse> clients, int total) {
        public ClientsResponse {
            clients = clients == null ? List.of() : List.copyOf(clients);
        }
    }
}

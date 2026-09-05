package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.token.ClaimShapes;
import io.flowcatalyst.platform.auth.token.ScopeNarrowing;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.principal.Principal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/// The token shapes an interactive login (authorization_code and its
/// refresh) receives (`docs/spec/auth-core.md` §7.3.4, §7.3.5; Go
/// `mintInteractiveAccessToken`, `mintIDToken`, `confineToClient`):
///
///   - **access token**: identity-only by default; an `apiAccess` client
///     gets an authority-bearing token narrowed to its own applications —
///     roles filtered to those applications, `applications` intersected,
///     `all_applications` off, the `scope` derived from the narrowed roles;
///   - **id token**: for an app-scoped client, roles and applications
///     confined the same way; an unscoped client sees the full claims.
///
/// "What this relying party may know about its user" has one definition,
/// shared with `/oauth/userinfo`, so the two cannot drift.
public final class InteractiveMint {

    private InteractiveMint() {
    }

    /// A principal as an app-scoped client is entitled to see it.
    public record Confined(List<String> roles, List<String> applicationIds, boolean allApplications) {
    }

    /// Roles narrowed to the client's applications (canonical names),
    /// applications intersected, all-applications forced off. An unscoped
    /// client (no application ids) sees everything.
    public static Confined confineToClient(OAuthState s, Principal p, OAuthClient client) {
        if (client == null || client.applicationIds().isEmpty()) {
            return new Confined(ClaimShapes.roleNames(p), p.accessibleApplicationIds(), p.allApplications());
        }
        List<String> roles = s.resolver().filterRolesForApplications(ClaimShapes.roleNames(p), client.applicationIds());
        return new Confined(roles, ClaimShapes.intersectApps(p, client.applicationIds()), false);
    }

    public static String accessToken(OAuthState s, Principal p, OAuthClient client, String requestedScope) {
        if (client == null) {
            return s.issuer().identityAccessToken(p, null);
        }
        if (!client.apiAccess()) {
            return s.issuer().identityAccessToken(p, client.clientId());
        }
        Confined c = confineToClient(s, p, client);
        // The ceiling comes from the NARROWED roles, so an app-scoped client
        // can never mint authority beyond its own applications.
        List<String> ceiling = s.resolver().flattenPermissions(c.roles());
        var granted = ScopeNarrowing.grant(ceiling, requestedScope);
        var authority = new TokenIssuer.Authority(
                ClaimShapes.clients(p, s.labels().clientIdentifiers(clientIdsOf(p))),
                c.roles(),
                ClaimShapes.applications(c.applicationIds(), c.allApplications(),
                        c.allApplications() ? Map.of() : s.labels().applicationCodes(c.applicationIds())),
                c.allApplications(),
                granted.permissions());
        return s.issuer().accessToken(p, authority, client.clientId());
    }

    public static String idToken(OAuthState s, Principal p, String audienceClientId, OAuthClient client, String nonce, Instant authTime) {
        Confined c = confineToClient(s, p, client);
        var in = new TokenIssuer.IdTokenInput(audienceClientId, nonce, authTime, c.roles(),
                ClaimShapes.applications(c.applicationIds(), c.allApplications(),
                        c.allApplications() ? Map.of() : s.labels().applicationCodes(c.applicationIds())),
                c.allApplications(),
                ClaimShapes.clients(p, s.labels().clientIdentifiers(clientIdsOf(p))));
        return s.issuer().idToken(p, in);
    }

    static List<String> clientIdsOf(Principal p) {
        return switch (p.scope()) {
            case ANCHOR -> List.of();
            case PARTNER -> p.assignedClients();
            case CLIENT -> p.clientId() == null ? List.of() : List.of(p.clientId());
        };
    }
}

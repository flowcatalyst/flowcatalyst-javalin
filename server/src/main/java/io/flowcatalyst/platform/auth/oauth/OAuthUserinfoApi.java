package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.token.ClaimShapes;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// `GET`/`POST /oauth/userinfo` (`docs/spec/auth-core.md` §6.2 O5, Go
/// `Userinfo` after `8ec7f9a`): "the id_token's claims as of now". The
/// bearer's `azp` names the relying party; the principal is re-loaded and
/// confined to that client's applications through the same
/// [InteractiveMint#confineToClient] the id_token mint uses, so userinfo
/// structurally cannot disclose more than the id_token did. Without a
/// principal (or an azp) the token's own claims are echoed.
public final class OAuthUserinfoApi {

    private OAuthUserinfoApi() {
    }

    public static void register(JavalinDefaultRoutingApi routes, OAuthState s) {
        routes.get("/oauth/userinfo", ctx -> userinfo(ctx, s));
        routes.post("/oauth/userinfo", ctx -> userinfo(ctx, s));
    }

    static void userinfo(Context ctx, OAuthState s) {
        String header = ctx.header("Authorization");
        if (header == null || header.isEmpty()) {
            OAuthError.of(401, "invalid_request", "Missing Authorization header").write(ctx);
            return;
        }
        String token = AccessTokenReader.bearer(header);
        if (token == null) {
            OAuthError.of(401, "invalid_request", "Invalid Authorization header format").write(ctx);
            return;
        }
        Optional<AccessTokenReader.Read> read = s.tokens().read(token);
        if (read.isEmpty()) {
            OAuthError.invalidToken("Token is invalid or expired").write(ctx);
            return;
        }
        TokenClaims c = read.get().claims();
        List<String> roles = c.roles();
        List<String> apps = c.applications();
        List<String> clients = c.clients();

        Optional<Principal> p = c.subject() == null ? Optional.empty()
                : s.principals().findById(c.subject()).filter(Principal::active);
        if (p.isPresent()) {
            roles = ClaimShapes.roleNames(p.get());
            apps = ClaimShapes.applications(p.get(), p.get().allApplications() ? Map.of()
                    : s.labels().applicationCodes(p.get().accessibleApplicationIds()));
            clients = ClaimShapes.clients(p.get(), s.labels().clientIdentifiers(InteractiveMint.clientIdsOf(p.get())));
            OAuthClient client = read.get().azp() == null ? null : s.oauthClients().findByClientId(read.get().azp()).orElse(null);
            if (client != null && !client.applicationIds().isEmpty()) {
                var confined = InteractiveMint.confineToClient(s, p.get(), client);
                roles = confined.roles();
                apps = ClaimShapes.applications(confined.applicationIds(), confined.allApplications(),
                        s.labels().applicationCodes(confined.applicationIds()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub", c.subject());
        if (c.email() != null && !c.email().isEmpty()) {
            body.put("email", c.email());
        }
        body.put("name", c.name() == null ? "" : c.name());
        body.put("tier", c.tier() == null ? "" : c.tier());
        body.put("scope", String.join(" ", c.permissions()));
        body.put("type", c.principalType() == null ? "" : c.principalType());
        String clientId = firstClientId(clients);
        if (clientId != null) {
            body.put("client_id", clientId);
        }
        body.put("clients", clients);
        body.put("roles", roles);
        body.put("applications", apps);
        ctx.status(200).json(body);
    }

    /// The first `clients` entry with its `:identifier` stripped; none for the wildcard.
    static String firstClientId(List<String> clients) {
        if (clients.isEmpty() || "*".equals(clients.getFirst())) {
            return null;
        }
        String first = clients.getFirst();
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(0, colon) : first;
    }
}

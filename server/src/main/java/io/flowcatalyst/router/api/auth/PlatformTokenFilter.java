package io.flowcatalyst.router.api.auth;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.Routes;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import io.flowcatalyst.platform.shared.auth.jwks.BearerAuthenticator;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The router API's guard outside dev mode (`docs/spec/router-api-auth.md`):
/// a platform-issued bearer token with `token_use = api`, and the permission
/// the route needs.
///
/// - `GET`/`HEAD`, and the in-flight check done over `POST`, need
///   [Permission#ROUTER_VIEW] (rule 2: the SDKs' stuck-message recovery calls
///   it).
/// - Every other method needs [Permission#ROUTER_OPERATE].
///
/// §9.7's public paths stay open ([BasicAuthFilter#isPublicPath]), and so do
/// the dashboard page and its two sign-in helpers (rule 4). The page carries
/// no data, and a signed-out browser has to load it before it can sign in.
///
/// With no [BearerAuthenticator] (no platform to verify against, rule 1) every
/// protected route answers 401. It fails closed, never open.
public final class PlatformTokenFilter implements Handler {

    /// The same realm §9.7's Basic challenge names.
    public static final String REALM = BasicAuthFilter.REALM;

    /// The header the dashboard reads to decide how to sign in (`BASIC` in
    /// dev mode, `BEARER` here).
    public static final String AUTH_MODE_HEADER = "X-Auth-Mode";

    /// The dashboard page and its sign-in helpers, relative to the mount prefix.
    static final Set<String> DASHBOARD_PATHS = Set.of(
            "/dashboard.html", "/monitoring/dashboard",
            "/dashboard/auth-config", "/dashboard/token");

    /// Reads done over `POST`: they take a body too large for a query string.
    static final Set<String> POST_READS = Set.of("/monitoring/in-flight-messages/check-batch");

    private final Optional<BearerAuthenticator> authenticator;
    private final String prefix;

    /// @param authenticator verifies platform tokens; empty when there is no
    ///                      platform to verify against (every protected route 401s)
    /// @param prefix        the router's mount prefix; `null`/blank means root
    public PlatformTokenFilter(Optional<BearerAuthenticator> authenticator, String prefix) {
        this.authenticator = authenticator;
        this.prefix = BasicAuthFilter.normalizePrefix(prefix);
    }

    @Override
    public void handle(Exchange ctx) {
        String path = BasicAuthFilter.stripPrefix(ctx.path(), prefix);
        if (BasicAuthFilter.isPublicPath(path) || DASHBOARD_PATHS.contains(path)) {
            return;
        }
        if (authenticator.isEmpty()) {
            unauthorized(ctx, "router API authentication is not configured: no platform to verify tokens against");
            return;
        }
        switch (authenticator.get().authenticate(ctx.header("Authorization"))) {
            case BearerAuthenticator.Rejected(String reason) -> unauthorized(ctx, reason);
            case BearerAuthenticator.Authenticated(TokenClaims claims) -> {
                if (!TokenClaims.TOKEN_USE_API.equals(claims.tokenUse())) {
                    // A session token or an identity token is not an API credential here, the
                    // same rule as the platform's own API.
                    unauthorized(ctx, "an API access token is required");
                    return;
                }
                Permission required = requiredPermission(ctx.method(), path);
                if (!Permission.grants(claims.permissions(), required.code())) {
                    ctx.status(403).json(Map.of("error", "PERMISSION_REQUIRED",
                            "message", required.code() + " required"));
                    ctx.skipRemainingHandlers();
                }
            }
        }
    }

    /// Rule 2: reads need `view`, everything else `operate`.
    static Permission requiredPermission(String method, String path) {
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || ("POST".equalsIgnoreCase(method) && POST_READS.contains(path));
        return read ? Permission.ROUTER_VIEW : Permission.ROUTER_OPERATE;
    }

    private static void unauthorized(Exchange ctx, String reason) {
        ctx.header("WWW-Authenticate", "Bearer realm=\"" + REALM + "\"");
        ctx.header(AUTH_MODE_HEADER, "BEARER");
        ctx.status(401).json(Map.of("error", "UNAUTHORIZED", "message", reason));
        // A `before` handler does not otherwise stop the matched route from running.
        ctx.skipRemainingHandlers();
    }

    /// Mounts this filter under `prefix + "/*"` (or `"/*"` at root).
    public static void register(Routes routes, PlatformTokenFilter filter) {
        String glob = filter.prefix.isEmpty() ? "/*" : filter.prefix + "/*";
        routes.before(glob, filter);
    }
}

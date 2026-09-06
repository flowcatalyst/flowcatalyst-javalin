package io.flowcatalyst.router.api.auth;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.Routes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

/// The router's optional HTTP BasicAuth guard (`docs/spec/router.md` §9.7,
/// Go `internal/router/api/auth.go`).
///
/// Credentials are constructor arguments, not read from [io.flowcatalyst.server.Env]
/// directly, so this class is testable without the process environment; the
/// caller passes `Env#routerAuthMode()`, `Env#routerAuthUser()`,
/// `Env#routerAuthPass()`.
///
/// ### The Go defect this does NOT reproduce
///
/// Go's middleware tests `r.URL.Path` against the public-path set, but chi
/// does not rewrite that field for a mounted sub-router — so under the
/// default `/router` mount, a probe request arrives as `/router/health/live`,
/// which is not in the public set, and probes/`/metrics` end up demanding
/// credentials whenever auth is configured (Go's own tests mount at root and
/// never catch this). Here the mount [#prefix] is stripped from the request
/// path **before** the public-path check, so `/router/health/live` is public
/// exactly as `/health/live` is.
public final class BasicAuthFilter implements Handler {

    /// Fixed per spec §9.7 — not configurable.
    public static final String REALM = "FlowCatalyst Router";

    private static final Set<String> PUBLIC_EXACT = Set.of(
            "/health", "/q/health",
            "/health/live", "/health/ready", "/health/startup",
            "/q/health/live", "/q/health/ready",
            "/metrics", "/q/metrics",
            "/ready");

    private final String prefix;
    private final byte[] expectedUser;
    private final byte[] expectedPass;
    private final boolean enabled;

    /// @param authMode `Env#routerAuthMode()` — raw `AUTH_MODE`; `NONE`
    ///                  (case-insensitive, after trimming) forces this filter off.
    /// @param username `Env#routerAuthUser()`; empty disables this filter.
    /// @param password `Env#routerAuthPass()`.
    /// @param prefix   the mount prefix the router is registered under (e.g.
    ///                 `/router`); `null`/blank means mounted at root.
    public BasicAuthFilter(String authMode, String username, String password, String prefix) {
        this.prefix = normalizePrefix(prefix);
        this.expectedUser = (username == null ? "" : username).getBytes(StandardCharsets.UTF_8);
        this.expectedPass = (password == null ? "" : password).getBytes(StandardCharsets.UTF_8);
        boolean authOff = authMode != null && authMode.trim().equalsIgnoreCase("NONE");
        this.enabled = !authOff && username != null && !username.isEmpty();
    }

    /// Whether this instance will enforce auth at all — `false` when
    /// `AUTH_MODE=NONE` or the username is empty (spec §9.7).
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void handle(Exchange ctx) {
        if (!enabled) {
            return;
        }
        String path = stripPrefix(ctx.path(), prefix);
        if (isPublicPath(path)) {
            return;
        }
        var creds = parseBasic(ctx.header("Authorization"));
        // Both halves are always compared when a Basic header is present —
        // never short-circuited on the username mismatching, so failure
        // timing cannot leak which half was wrong (spec §9.7).
        boolean userOk = creds != null
                && MessageDigest.isEqual(creds[0].getBytes(StandardCharsets.UTF_8), expectedUser);
        boolean passOk = creds != null
                && MessageDigest.isEqual(creds[1].getBytes(StandardCharsets.UTF_8), expectedPass);
        if (!userOk || !passOk) {
            ctx.header("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
            ctx.status(401).result("unauthorized");
            // A `before` handler does not otherwise stop the matched route
            // handler from running afterwards and overwriting this response.
            ctx.skipRemainingHandlers();
        }
    }

    /// Mounts this filter under `prefix + "/*"` (or `"/*"` for a root mount)
    /// — a no-op registration when the filter is [#enabled()] `false`, so
    /// callers can wire it unconditionally.
    public static void register(Routes routes, BasicAuthFilter filter) {
        if (!filter.enabled()) {
            return;
        }
        String glob = filter.prefix.isEmpty() ? "/*" : filter.prefix + "/*";
        routes.before(glob, filter);
    }

    /// `""` (root mount) or a leading-slash, no-trailing-slash prefix.
    static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String p = prefix.trim();
        if (p.isEmpty() || p.equals("/")) {
            return "";
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /// Strips `prefix` from `path`, leaving the path Go would see with the
    /// sub-router mount already accounted for. A path that does not actually
    /// start with `prefix` is returned unchanged (defensive; should not
    /// happen given how [#register] scopes the filter).
    static String stripPrefix(String path, String prefix) {
        if (prefix.isEmpty()) {
            return path;
        }
        if (path.equals(prefix)) {
            return "/";
        }
        if (path.startsWith(prefix + "/")) {
            return path.substring(prefix.length());
        }
        return path;
    }

    /// `router/api/auth.go`'s `IsPublicPath`, plus the spec's `/openapi*.{json,yaml}`
    /// glob (§9.7) rather than Go's hard-coded four-file list, and anything
    /// under `/docs`.
    static boolean isPublicPath(String path) {
        if (PUBLIC_EXACT.contains(path)) {
            return true;
        }
        if (path.equals("/docs") || path.startsWith("/docs/")) {
            return true;
        }
        return path.startsWith("/openapi") && (path.endsWith(".json") || path.endsWith(".yaml"));
    }

    /// `null` when the header is missing or not well-formed `Basic <base64(user:pass)>`.
    private static String[] parseBasic(String header) {
        if (header == null || header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(header.substring(6).trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String s = new String(decoded, StandardCharsets.UTF_8);
        int idx = s.indexOf(':');
        if (idx < 0) {
            return null;
        }
        return new String[]{s.substring(0, idx), s.substring(idx + 1)};
    }
}

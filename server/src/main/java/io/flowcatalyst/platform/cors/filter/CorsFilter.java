package io.flowcatalyst.platform.cors.filter;

import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;

import java.util.Map;
import java.util.Objects;

/// The CORS response-header filter (`docs/spec/cors.md` §9): a `before`
/// handler that answers a preflight directly and, for every request carrying
/// an `Origin` header, decides whether the platform's response may say so.
///
/// A request is a **preflight** when it is `OPTIONS` and carries
/// `Access-Control-Request-Method` — the two together are what a browser
/// sends ahead of a cross-origin request it expects to need permission for;
/// an `OPTIONS` request without that header is an ordinary route, not CORS.
///
/// **Never credentialed** (2026-10-14, the F16 follow-up): the filter never sends
/// `Access-Control-Allow-Credentials`. The platform's cookie session is for its own
/// same-origin SPA; a page on an allow-listed origin that sent the user's cookie
/// (`credentials: "include"`) gets no grant, so the browser neither sends the
/// credentialed non-simple request nor exposes a credentialed response to it.
/// `SessionCsrf` refuses such writes; this is what keeps such reads closed. A
/// cross-origin caller that authenticates with its own bearer token needs no
/// credentials flag and keeps the allow-list.
///
/// Behaviour:
///   - Every request on the platform surface, `Origin` or not, gets `Vary: Origin`
///     **appended** (never replacing an existing `Vary`): the same URL is answered
///     with or without `Access-Control-Allow-Origin` depending on the caller's
///     `Origin`, so a shared cache must not serve one caller's copy to another
///     (Fetch, "CORS protocol and HTTP caches").
///   - No `Origin` header: not a cross-origin request: nothing else is added and
///     the chain runs.
///   - `Origin` present and [CorsAllowlist#matches] is `true`: the response
///     echoes the origin back verbatim on `Access-Control-Allow-Origin` — never
///     `*` (the allowlist is a list) and never a value that did not match.
///   - `Origin` present, allowed, and the request is a preflight: status 204
///     with no body, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`
///     (the request's `Access-Control-Request-Headers` echoed, else
///     `Authorization, Content-Type, X-Requested-With`), `Access-Control-Max-Age: 600`,
///     and [Context#skipRemainingHandlers] — the route's own handler never
///     runs for a preflight.
///   - `Origin` present, **not** allowed, and the request is a preflight:
///     the platform's `403 CORS_ORIGIN_NOT_ALLOWED` envelope, and the chain
///     stops the same way.
///   - `Origin` present, not allowed, and the request is **not** a
///     preflight: no CORS headers are added and the request is processed
///     normally — enforcement of a disallowed origin is the browser's job
///     (it will not expose the response to the disallowed page); the
///     platform does not also have to refuse the request server-side.
public final class CorsFilter implements Handler {

    static final String ORIGIN = "Origin";
    static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    static final String VARY = "Vary";
    static final String ACCESS_CONTROL_REQUEST_HEADERS = "Access-Control-Request-Headers";
    static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    static final String ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";
    /// Spec §9.3: the preflight answer for an allowed origin.
    static final String ALLOWED_METHODS = "GET, POST, PUT, PATCH, DELETE, OPTIONS";
    static final String DEFAULT_ALLOWED_HEADERS = "Authorization, Content-Type, X-Requested-With";
    static final String MAX_AGE_SECONDS = "600";

    private final CorsAllowlist allowlist;

    public CorsFilter(CorsAllowlist allowlist) {
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
    }

    @Override
    public void handle(Exchange ctx) {
        // The answer for this URL depends on the caller's Origin (headers set or not),
        // so every response says so, including one to a caller that sent none — append,
        // never replace.
        ctx.addHeader(VARY, ORIGIN);
        var origin = ctx.header(ORIGIN);
        if (origin == null) {
            return;
        }

        boolean allowed = allowlist.matches(origin);
        if (allowed) {
            // No Access-Control-Allow-Credentials, ever: see the class comment.
            ctx.header(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        }

        if (!isPreflight(ctx)) {
            return;
        }
        if (allowed) {
            // Spec §9.3: methods, the requested headers echoed (else the default
            // trio), and a ten-minute cache of this answer.
            var requested = ctx.header(ACCESS_CONTROL_REQUEST_HEADERS);
            ctx.header(ACCESS_CONTROL_ALLOW_METHODS, ALLOWED_METHODS);
            ctx.header(ACCESS_CONTROL_ALLOW_HEADERS, requested == null || requested.isBlank() ? DEFAULT_ALLOWED_HEADERS : requested);
            ctx.header(ACCESS_CONTROL_MAX_AGE, MAX_AGE_SECONDS);
            ctx.status(204);
        } else {
            HttpError.write(ctx, 403, "CORS_ORIGIN_NOT_ALLOWED", "Origin not allowed: " + origin, Map.of());
        }
        ctx.skipRemainingHandlers();
    }

    private static boolean isPreflight(Exchange ctx) {
        return "OPTIONS".equals(ctx.method()) && ctx.header(ACCESS_CONTROL_REQUEST_METHOD) != null;
    }
}

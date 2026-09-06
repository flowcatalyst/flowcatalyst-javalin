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
/// Behaviour:
///   - No `Origin` header: not a cross-origin request: this filter does
///     nothing and lets the chain run.
///   - `Origin` present and [CorsAllowlist#matches] is `true`: the response
///     echoes the origin back verbatim on `Access-Control-Allow-Origin`
///     (never `*` — a wildcard origin is incompatible with credentials) and
///     sets `Access-Control-Allow-Credentials: true`.
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
///   - Any response that depended on the `Origin` header value gets
///     `Vary: Origin` **appended** (never replacing an existing `Vary`) so a
///     shared cache does not serve one origin's CORS headers to another.
public final class CorsFilter implements Handler {

    static final String ORIGIN = "Origin";
    static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    static final String ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
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
        var origin = ctx.header(ORIGIN);
        if (origin == null) {
            return;
        }
        // The response differs by the caller's Origin (headers set or not) whenever
        // one was sent, regardless of the allow/deny outcome — append, never replace.
        ctx.addHeader(VARY, ORIGIN);

        boolean allowed = allowlist.matches(origin);
        if (allowed) {
            ctx.header(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            ctx.header(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
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

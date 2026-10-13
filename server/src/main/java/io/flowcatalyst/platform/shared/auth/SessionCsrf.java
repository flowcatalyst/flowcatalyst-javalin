package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.HttpException;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The cookie-session request guard (Go `middleware.SessionCSRF`,
/// `docs/go-mirror/2026-10-14-platform-bug-fixes-7.md`). A `before` filter
/// registered right after the [Authenticator]: it applies only when the
/// request's [AuthContext] came from the session cookie
/// ([AuthContext#viaSessionCookie]) and the method is unsafe (anything but
/// `GET`, `HEAD`, `OPTIONS`):
///
///  1. **Provenance** (Go's `net/http.CrossOriginProtection`, plus one rule):
///     `Sec-Fetch-Site` `same-origin` or `none` passes; any other value passes
///     only when `Origin` is a trusted origin. Without `Sec-Fetch-Site`, the
///     `Origin` header's host must equal the request's [Exchange#host] or the
///     `Origin` must be a trusted origin (the public base URL, for a proxy
///     that rewrites `Host`). A request with **neither** header is refused
///     too (no browser sends a POST, PUT, PATCH or DELETE without one).
///     Refusal: 403 `CROSS_ORIGIN_REQUEST`, logged.
///  2. **JSON only**: a request with a body (a non-zero or unknown length)
///     must declare `application/json` or an `application/*+json` type;
///     `text/plain`, forms, multipart (what a page on another origin can send
///     without a CORS preflight), a malformed `Content-Type` and none at all
///     are refused: 415 `UNSUPPORTED_MEDIA_TYPE`.
///
/// `SameSite=Lax` already keeps the cookie off cross-site POSTs; this covers
/// what Lax does not — a sibling subdomain of the same registrable domain
/// (same-site, so the cookie is sent) and browsers that ignore SameSite.
///
/// **Not affected**: bearer-authenticated requests (the bearer wins when both
/// are sent, so the context is not the cookie's), the dev test headers, a
/// stale or invalid cookie (it authenticates nothing), every route outside
/// the authenticator ([io.flowcatalyst.server.Platform#isPublicPath]: login,
/// 2FA, password reset, the dispatch callbacks) and the OAuth/OIDC surface
/// (`/oauth/*`, `/.well-known/*`), which Go mounts outside its authenticated
/// group although this port's authenticator runs for most of it.
public final class SessionCsrf implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(SessionCsrf.class);

    /// Wire codes of the guard's refusals (Go `CodeCrossOriginRequest`, `CodeUnsupportedMediaType`).
    public static final String CROSS_ORIGIN_REQUEST = "CROSS_ORIGIN_REQUEST";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    static final String CROSS_ORIGIN_MESSAGE = "This request was refused because it did not come from this site. "
            + "Requests authenticated by the session cookie must be sent by the platform's own pages; "
            + "API clients authenticate with a bearer token.";
    static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE =
            "Requests authenticated by the session cookie must send a JSON body (Content-Type: application/json).";

    private final Provenance provenance;

    /// `trustedOrigins`: `scheme://host[:port]` origins, compared with the
    /// `Origin` header exactly. A blank entry is skipped; anything that is not
    /// an http(s) origin is a configuration error.
    public SessionCsrf(List<String> trustedOrigins) {
        this.provenance = new Provenance(trustedOrigins);
    }

    /// The public base URL (`FC_JWT_ISSUER`) as the one trusted origin, as
    /// Go's `publicOrigins`: a value that is not an http(s) URL is left out,
    /// with a warning, rather than failing the platform's boot.
    public static List<String> publicOrigins(String base) {
        try {
            var u = new URI(base);
            if (("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && u.getRawAuthority() != null
                    && u.getHost() != null) {
                return List.of(u.getScheme() + "://" + u.getRawAuthority());
            }
        } catch (URISyntaxException | NullPointerException _) {
            // fall through
        }
        LOG.atWarn().setMessage("public base URL is not an http(s) URL; cookie-session posts are accepted from the request's own host only")
                .addKeyValue("value", base).log();
        return List.of();
    }

    @Override
    public void handle(Exchange ctx) {
        if (safeMethod(ctx.method())) return;
        AuthContext ac = Auth.from(ctx);
        if (ac == null || !ac.viaSessionCookie()) return;
        String path = ctx.path();
        if (path.startsWith("/oauth/") || path.startsWith("/.well-known/")) return;

        String secFetchSite = ctx.header("Sec-Fetch-Site");
        String origin = ctx.header("Origin");
        String refusal = provenance.check(secFetchSite, origin, ctx.host(), true);
        if (refusal != null) {
            LOG.atWarn().setMessage("cookie-session request refused: not from this origin")
                    .addKeyValue("method", ctx.method())
                    .addKeyValue("path", path)
                    .addKeyValue("origin", origin)
                    .addKeyValue("secFetchSite", secFetchSite)
                    .addKeyValue("err", refusal)
                    .log();
            refuse(ctx, 403, CROSS_ORIGIN_REQUEST, CROSS_ORIGIN_MESSAGE);
            return;
        }
        String contentType = ctx.header("Content-Type");
        if (hasBody(ctx) && !isJsonMediaType(contentType) && !isArtifactUpload(ctx.method(), path, contentType)) {
            refuse(ctx, 415, UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_MESSAGE);
        }
    }

    private static final java.util.regex.Pattern ARTIFACT_UPLOAD = java.util.regex.Pattern.compile("^/api/functions/[^/]+/artifacts/[^/]+$");

    /// The one raw-bytes route this port's SPA sends with the cookie: the
    /// Publish drawer's `PUT /api/functions/{address}/artifacts/{digest}`
    /// (`application/octet-stream`; the CLI sends `application/wasm` with a
    /// bearer). Divergence from Go, whose SPA uploads nothing: here the JSON
    /// rule would break the drawer. Provenance still applies, and neither type
    /// is CORS-safelisted, so another origin cannot send one without the
    /// preflight the platform answers only for an allow-listed origin.
    static boolean isArtifactUpload(String method, String path, String contentType) {
        if (!method.equals("PUT") || contentType == null || !ARTIFACT_UPLOAD.matcher(path).matches()) return false;
        String mediaType = parseMediaType(contentType);
        return "application/octet-stream".equals(mediaType) || "application/wasm".equals(mediaType);
    }

    static boolean safeMethod(String m) {
        return m.equals("GET") || m.equals("HEAD") || m.equals("OPTIONS");
    }

    /// A non-zero `Content-Length`, or an unknown one that turns out to carry
    /// bytes (chunked, HTTP/2 without a length). A streaming route's body has
    /// not been read yet, so an unknown length there counts as a body.
    static boolean hasBody(Exchange ctx) {
        long length = ctx.contentLength();
        if (length > 0) return true;
        if (length == 0) return false;
        if (ctx.header("Transfer-Encoding") != null) return true;
        try {
            return ctx.bodyAsBytes().length > 0;
        } catch (IllegalStateException | HttpException _) {
            return true;
        }
    }

    /// `application/json` or any `application/*+json`, parameters allowed
    /// (Go `mime.ParseMediaType`: a malformed value is not JSON).
    static boolean isJsonMediaType(String contentType) {
        if (contentType == null || contentType.isEmpty()) return false;
        String mediaType = parseMediaType(contentType);
        if (mediaType == null) return false;
        return mediaType.equals("application/json")
                || (mediaType.startsWith("application/") && mediaType.endsWith("+json"));
    }

    /// The lower-cased `type/subtype` of a `Content-Type` value whose
    /// parameters are well formed (`; token=token-or-quoted-string`, no
    /// duplicate names, a trailing `;` tolerated), else `null`.
    static String parseMediaType(String value) {
        int semi = value.indexOf(';');
        String mt = (semi < 0 ? value : value.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        int slash = mt.indexOf('/');
        if (slash <= 0 || slash == mt.length() - 1 || !isToken(mt.substring(0, slash)) || !isToken(mt.substring(slash + 1))) {
            return null;
        }
        if (semi < 0) return mt;
        var seen = new HashSet<String>();
        String rest = value.substring(semi);
        int i = 0;
        while (i < rest.length()) {
            // at a ';'
            i++;
            while (i < rest.length() && isSpace(rest.charAt(i))) i++;
            if (i >= rest.length()) break; // trailing ';'
            int keyStart = i;
            while (i < rest.length() && isTokenChar(rest.charAt(i))) i++;
            String key = rest.substring(keyStart, i).toLowerCase(Locale.ROOT);
            while (i < rest.length() && isSpace(rest.charAt(i))) i++;
            if (key.isEmpty() || i >= rest.length() || rest.charAt(i) != '=') return null;
            i++;
            while (i < rest.length() && isSpace(rest.charAt(i))) i++;
            if (i < rest.length() && rest.charAt(i) == '"') {
                i++;
                boolean closed = false;
                while (i < rest.length()) {
                    char c = rest.charAt(i);
                    if (c == '\\' && i + 1 < rest.length()) {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (c == '"') {
                        closed = true;
                        break;
                    }
                }
                if (!closed) return null;
            } else {
                int valueStart = i;
                while (i < rest.length() && isTokenChar(rest.charAt(i))) i++;
                if (i == valueStart) return null;
            }
            while (i < rest.length() && isSpace(rest.charAt(i))) i++;
            if (i < rest.length() && rest.charAt(i) != ';') return null;
            if (!seen.add(key)) return null;
        }
        return mt;
    }

    private static boolean isToken(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isTokenChar(s.charAt(i))) return false;
        }
        return true;
    }

    /// RFC 7230 `tchar`.
    private static boolean isTokenChar(char c) {
        if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') return true;
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    static void refuse(Exchange ctx, int status, String code, String message) {
        HttpError.write(ctx, status, code, message, Map.of());
        ctx.header("Cache-Control", "no-store");
        ctx.skipRemainingHandlers();
    }
}

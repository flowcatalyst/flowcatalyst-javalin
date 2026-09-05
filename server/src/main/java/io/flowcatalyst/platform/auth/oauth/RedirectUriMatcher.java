package io.flowcatalyst.platform.auth.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/// The one matcher for both `/oauth/authorize`'s `redirect_uri` and the
/// OIDC `post_logout_redirect_uri` (`docs/spec/auth-core.md` §7.3.2, Go
/// `MatchRedirectURI`), so the two cannot drift. Strict and
/// URL-component-aware:
///
///   - an exact string match always passes;
///   - otherwise the incoming URI must parse with a scheme and host and
///     carry **no userinfo** (`https://victim@evil.com`), and only
///     wildcard patterns are considered — a non-wildcard pattern matches
///     exactly or not at all;
///   - scheme equal (case-insensitively), port equal;
///   - the host wildcard lives only in the leftmost label — full (`*.x.com`)
///     or partial (`qa-*.x.com`, `*-qa.x.com`) — over a concrete base of at
///     least two labels; each `*` consumes at least one character and never
///     a dot;
///   - path: pattern `""`/`/` ⇒ any path; trailing `/*` ⇒ prefix; else
///     exact. The incoming query and fragment are ignored.
public final class RedirectUriMatcher {

    private RedirectUriMatcher() {
    }

    public static boolean matches(String uri, List<String> registered) {
        if (registered.contains(uri)) {
            return true;
        }
        URI u;
        try {
            u = new URI(uri);
        } catch (URISyntaxException e) {
            return false;
        }
        if (u.getRawUserInfo() != null || u.getHost() == null || u.getHost().isEmpty() || u.getScheme() == null) {
            return false;
        }
        for (String pattern : registered) {
            if (pattern.indexOf('*') < 0) {
                continue;
            }
            if (matchOne(u, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchOne(URI u, String pattern) {
        // A wildcard host is not a legal URI authority, so the pattern is
        // split by hand rather than through java.net.URI (whose host would
        // come back null for "*.x.com").
        Pattern p = Pattern.parse(pattern);
        if (p == null || p.host().isEmpty()) {
            return false;
        }
        if (!u.getScheme().equalsIgnoreCase(p.scheme())) {
            return false;
        }
        if (u.getPort() != p.port()) {
            return false;
        }
        if (!hostMatches(u.getHost().toLowerCase(Locale.ROOT), p.host().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return pathMatches(u.getRawPath() == null ? "" : u.getRawPath(), p.path());
    }

    /// `scheme://host[:port][/path][?...][#...]`; port `-1` when absent, as
    /// [URI#getPort()] reports it.
    record Pattern(String scheme, String host, int port, String path) {
        static Pattern parse(String pattern) {
            int sep = pattern.indexOf("://");
            if (sep <= 0) {
                return null;
            }
            String scheme = pattern.substring(0, sep);
            String rest = pattern.substring(sep + 3);
            int end = rest.length();
            for (char c : new char[] {'/', '?', '#'}) {
                int i = rest.indexOf(c);
                if (i >= 0 && i < end) {
                    end = i;
                }
            }
            String authority = rest.substring(0, end);
            String path = end < rest.length() && rest.charAt(end) == '/' ? rest.substring(end) : "";
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            int f = path.indexOf('#');
            if (f >= 0) {
                path = path.substring(0, f);
            }
            if (authority.indexOf('@') >= 0) {
                return null; // userinfo is never part of a registered pattern
            }
            String host = authority;
            int port = -1;
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                try {
                    port = Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return new Pattern(scheme, host, port, path);
        }
    }

    static boolean hostMatches(String host, String pattern) {
        if (pattern.indexOf('*') < 0) {
            return host.equals(pattern);
        }
        int dot = pattern.indexOf('.');
        if (dot < 0) {
            return false; // bare "*" — no base domain
        }
        String pLabel = pattern.substring(0, dot);
        String pBase = pattern.substring(dot + 1);
        if (pBase.indexOf('*') >= 0) {
            return false; // wildcards only in the leftmost label
        }
        if (pBase.indexOf('.') < 0) {
            return false; // base must have >= 2 labels
        }
        int hdot = host.indexOf('.');
        if (hdot < 0 || !host.substring(hdot + 1).equals(pBase)) {
            return false;
        }
        return labelMatches(host.substring(0, hdot), pLabel);
    }

    /// A single dot-free label against a pattern with `*` wildcards: literal
    /// segments in order, every `*` consuming at least one character.
    static boolean labelMatches(String label, String pattern) {
        if (label.isEmpty()) {
            return false;
        }
        if (pattern.indexOf('*') < 0) {
            return label.equals(pattern);
        }
        String[] segs = pattern.split("\\*", -1);
        if (!label.startsWith(segs[0])) {
            return false;
        }
        String rest = label.substring(segs[0].length());
        for (int i = 1; i < segs.length; i++) {
            String seg = segs[i];
            if (i == segs.length - 1) {
                if (seg.isEmpty()) {
                    return !rest.isEmpty();
                }
                return rest.endsWith(seg) && rest.length() > seg.length();
            }
            int idx = rest.indexOf(seg);
            if (idx < 1) {
                return false;
            }
            rest = rest.substring(idx + seg.length());
        }
        return true;
    }

    static boolean pathMatches(String uriPath, String patternPath) {
        if (patternPath.isEmpty() || patternPath.equals("/")) {
            return true;
        }
        if (patternPath.endsWith("/*")) {
            return uriPath.startsWith(patternPath.substring(0, patternPath.length() - 1));
        }
        return uriPath.equals(patternPath);
    }
}

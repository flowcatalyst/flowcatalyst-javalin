package io.flowcatalyst.platform.shared;

import java.net.URI;
import java.net.URISyntaxException;

/// The one rule for a caller-supplied redirect that must stay on this origin
/// (`docs/spec/security-fixes-2026-09-24.md` S2.6): the OIDC bridge's
/// `return_url`, the password-reset and password-setup redirects.
///
/// A browser is more forgiving than a prefix check: it strips tab and
/// newline from a URL and reads `\` as `/`, so `/<TAB>/evil.com` and
/// `/\evil.com` both leave the site as `//evil.com`. The value is therefore
/// judged both as written and as a parsed [URI], and refused when any of
/// these hold:
///
/// | rule                     | example refused            |
/// |--------------------------|----------------------------|
/// | not starting with one `/`| `evil.com`, `` (empty)     |
/// | a scheme                 | `https://evil.com`, `javascript:x` |
/// | an authority (`//` as written) | `//evil.com`, `///evil.com` |
/// | a backslash (raw or decoded) | `/\evil.com`, `/%5Cevil.com` |
/// | a control or whitespace character (raw or decoded) | `/\t/evil.com`, `/%09/evil.com` |
/// | a decoded path that starts `//` | `/%2F/evil.com`      |
/// | not a parseable URI      | `/a b`, `/%zz`             |
///
/// A plain path with a query (`/oauth/authorize?client_id=…`) passes.
public final class RelativeRedirect {

    private RelativeRedirect() {
    }

    /// Whether `candidate` is a same-origin relative redirect; `null` is not.
    public static boolean isSafe(String candidate) {
        // `//` as written is refused before parsing: `URI` reads `///evil.com` as an empty
        // authority and a path, where a browser reads the host `evil.com`.
        if (candidate == null || !candidate.startsWith("/") || candidate.startsWith("//") || hasForbiddenCharacter(candidate)) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getScheme() != null || uri.getRawAuthority() != null || uri.isOpaque()) {
            return false;
        }
        String path = uri.getPath();
        return path != null && path.startsWith("/") && !path.startsWith("//") && !hasForbiddenCharacter(path);
    }

    private static boolean hasForbiddenCharacter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return true;
            }
        }
        return false;
    }
}

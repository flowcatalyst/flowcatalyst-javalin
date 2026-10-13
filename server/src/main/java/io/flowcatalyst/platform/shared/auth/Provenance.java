package io.flowcatalyst.platform.shared.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Whether an unsafe request comes from the platform's own origin, as a
/// browser reports it (Go's `net/http.CrossOriginProtection`), shared by
/// [SessionCsrf] and [CredentialCsrf]:
///
/// - `Sec-Fetch-Site` `same-origin` or `none` passes; another value passes
///   only when `Origin` is a trusted origin;
/// - without it, `Origin`'s host must equal the request's host, or `Origin`
///   must be a trusted origin (the public base URL, for a proxy that rewrites
///   `Host`); `Origin: null` and a malformed one name no host;
/// - with neither header: refused by [SessionCsrf] (the request presents the
///   browser's cookie), passed by [CredentialCsrf] (not a browser; login CSRF
///   needs the victim's browser, and every browser sends `Origin` on a
///   cross-origin POST).
final class Provenance {

    private final Set<String> trustedOrigins;

    Provenance(List<String> trustedOrigins) {
        var set = new HashSet<String>();
        for (String o : trustedOrigins) {
            if (o == null || o.isBlank()) continue;
            URI u;
            try {
                u = new URI(o);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("trusted origin '" + o + "' is not an http(s) origin", e);
            }
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getRawAuthority() == null) {
                throw new IllegalArgumentException("trusted origin '" + o + "' is not an http(s) origin");
            }
            set.add(u.getScheme() + "://" + u.getRawAuthority());
        }
        this.trustedOrigins = Set.copyOf(set);
    }

    /// `null` when the request comes from this origin, else why not.
    /// `refuseWithoutHeaders`: whether a request with neither header is refused.
    String check(String secFetchSite, String origin, String host, boolean refuseWithoutHeaders) {
        if (secFetchSite != null && !secFetchSite.isEmpty()) {
            if (secFetchSite.equals("same-origin") || secFetchSite.equals("none")) return null;
            if (origin != null && trustedOrigins.contains(origin)) return null;
            return "cross-origin request detected from Sec-Fetch-Site header";
        }
        if (origin == null || origin.isEmpty()) {
            return refuseWithoutHeaders ? "unsafe request without Sec-Fetch-Site or Origin" : null;
        }
        if (host != null && !host.isEmpty() && host.equals(originHost(origin))) return null;
        if (trustedOrigins.contains(origin)) return null;
        return "cross-origin request detected, and/or browser is out of date: Sec-Fetch-Site is missing, and Origin does not match Host";
    }

    /// The `host[:port]` of an `Origin` value (Go `url.Parse(origin).Host`),
    /// `null` when it has none (`null`, an opaque or malformed origin).
    private static String originHost(String origin) {
        try {
            return new URI(origin).getRawAuthority();
        } catch (URISyntaxException _) {
            return null;
        }
    }
}

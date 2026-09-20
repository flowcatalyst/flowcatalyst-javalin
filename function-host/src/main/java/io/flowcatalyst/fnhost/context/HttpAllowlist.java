package io.flowcatalyst.fnhost.context;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// The `manifest.httpAllow` matcher (spec `function-context.md` §2): an
/// entry is an exact host name, or `*.suffix` matching **subdomains only** —
/// not the apex, and not a host that merely ends with the suffix string
/// (`CONVENTIONS.md` §8 "a matcher is a pinned table" — `evilsuffix.test`
/// must never match `*.suffix.test`, so the comparison is a dot-bounded
/// label match, never `String#endsWith`).
public final class HttpAllowlist {

    private final List<String> exact;
    private final List<String> suffixes;

    public HttpAllowlist(List<String> entries) {
        Objects.requireNonNull(entries, "entries");
        exact = entries.stream()
                .filter(e -> e != null && !e.startsWith("*."))
                .map(e -> e.toLowerCase(Locale.ROOT))
                .toList();
        suffixes = entries.stream()
                .filter(e -> e != null && e.startsWith("*."))
                .map(e -> e.substring(2).toLowerCase(Locale.ROOT))
                .toList();
    }

    /// `host` is `null`-safe (a URI with no host is always rejected).
    public boolean allows(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String e : exact) {
            if (h.equals(e)) {
                return true;
            }
        }
        for (String suffix : suffixes) {
            // Subdomains only: h must be strictly longer than the suffix and the
            // character immediately before the suffix must be the label separator
            // '.' — "evilsuffix.test" must not match "*.suffix.test", and the bare
            // apex "suffix.test" must not match either (methods §8's "matcher is a
            // pinned table": endsWith alone would accept both).
            if (h.length() > suffix.length() + 1
                    && h.charAt(h.length() - suffix.length() - 1) == '.'
                    && h.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /// `localhost` / `127.0.0.1` / `::1` — the loopback exception to the
    /// `https`-only rule (spec §2: "`https` only unless the host is
    /// `localhost`/`127.0.0.1`"). IPv6 loopback is included for completeness;
    /// the spec's own wording names only the two IPv4-era spellings.
    public static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    }
}

package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.http.Exchange;

/// The caller's address for backoff and login-attempt rows (Go
/// `ratelimit.ClientIP`): the **rightmost** `X-Forwarded-For` hop — the one
/// appended by the proxy we trust, since anything left of it is
/// caller-supplied — else the remote address without its port.
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(Exchange ctx) {
        String rightmost = rightmostForwardedFor(ctx.header("X-Forwarded-For"));
        if (!rightmost.isEmpty()) {
            return rightmost;
        }
        String host = ctx.ip();
        if (host == null) {
            return "";
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) { // "1.2.3.4:5678", not an IPv6 literal
            host = host.substring(0, colon);
        }
        return host.trim();
    }

    /// `""` when the header is absent or degenerate.
    public static String rightmostForwardedFor(String xff) {
        if (xff == null || xff.isBlank()) {
            return "";
        }
        String[] parts = xff.split(",");
        return parts[parts.length - 1].trim();
    }
}

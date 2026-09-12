package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.HttpCookie;

/// The `fc_session` cookie's attributes (`docs/spec/auth-core.md` §6.1,
/// "Cookie attributes"): `Path=/`, `HttpOnly`, `SameSite=Lax`, `Max-Age`
/// configurable (default 24 h; the deployed environment sets 8 h — owner
/// ruling 2026-09-11 supersedes C-Q16's "session TTL is compile-time",
/// `docs/spec/deployed-dispatch.md` §4), `Secure` unless test headers are
/// allowed (a local HTTP dev box). `maxAgeSeconds` must equal the session
/// JWT's lifetime ([TokenIssuer.Config#sessionTtlSeconds()]) — the two are
/// set independently by the composition root and nothing else enforces they
/// agree.
public final class SessionCookie {

    public static final String NAME = Authenticator.SESSION_COOKIE;

    private final int maxAgeSeconds;
    private final boolean secure;

    /// @param secure        `!FC_AUTH_ALLOW_TEST_HEADERS` (Go `CookieSecure`)
    /// @param maxAgeSeconds the cookie's `Max-Age` — must equal the minting
    ///                      [TokenIssuer]'s `config().sessionTtlSeconds()`
    public SessionCookie(boolean secure, int maxAgeSeconds) {
        this.secure = secure;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public boolean secure() {
        return secure;
    }

    public void set(Exchange ctx, String token) {
        ctx.cookie(new HttpCookie(NAME, token, "/", maxAgeSeconds, true, secure, HttpCookie.SameSite.LAX));
    }

    /// An expired cookie with the same attributes — Go writes `MaxAge: -1`,
    /// which `net/http` renders as `Max-Age=0`; Javalin treats a negative
    /// max-age as "unset", so the zero is written explicitly.
    public void clear(Exchange ctx) {
        // Written by hand: Jetty renders a zero max-age as an `Expires=` in the past;
        // Go's net/http writes `Max-Age=0`, and the wire should read the same (parity S2).
        ctx.header("Set-Cookie", NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
    }
}

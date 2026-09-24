package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.auth.mfa.TrustedDeviceCookie;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.HttpCookie;

/// The session cookie's attributes (`docs/spec/auth-core.md` §6.1, "Cookie
/// attributes"; `docs/spec/cookie-hardening.md` §3): `Path=/`, `HttpOnly`,
/// `SameSite=Lax`, `Max-Age` configurable (default 24 h; the deployed
/// environment sets 8 h — owner ruling 2026-09-11 supersedes C-Q16's
/// "session TTL is compile-time", `docs/spec/deployed-dispatch.md` §4),
/// `Secure` unless test headers are allowed (a local HTTP dev box).
/// `maxAgeSeconds` must equal the session JWT's lifetime
/// ([TokenIssuer.Config#sessionTtlSeconds()]) — the two are set
/// independently by the composition root and nothing else enforces they
/// agree.
///
/// **Owns the cookie's name**: `__Host-fc_session` when secure — a browser
/// refuses that prefix over plain HTTP or on a `Set-Cookie` carrying
/// `Domain`, so it can only ever have been set by this exact origin —
/// `fc_session` otherwise (constants beside [TrustedDeviceCookie]'s own).
/// There is no more static `NAME`: a caller asks a live instance, and the
/// platform's `Authenticator` is handed the same name as a plain `String`
/// ([#nameFor(boolean)]) rather than this type, so the enforcement side
/// never drifts from what this side mints.
public final class SessionCookie {

    public static final String SECURE_NAME = Authenticator.SECURE_SESSION_COOKIE;
    public static final String INSECURE_NAME = Authenticator.INSECURE_SESSION_COOKIE;

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

    /// [#SECURE_NAME] or [#INSECURE_NAME], according to [#secure()].
    public String name() {
        return nameFor(secure);
    }

    /// [#name()] without needing an instance — the composition root uses
    /// this to derive the ONE cookie name it hands `Authenticator.Config`,
    /// from the SAME `cookiesSecure` decision that builds every
    /// `SessionCookie` it mints (`docs/spec/cookie-hardening.md` §3: "one
    /// decision, passed to both").
    public static String nameFor(boolean secure) {
        return secure ? SECURE_NAME : INSECURE_NAME;
    }

    public void set(Exchange ctx, String token) {
        ctx.cookie(new HttpCookie(name(), token, "/", maxAgeSeconds, true, secure, HttpCookie.SameSite.LAX));
    }

    /// An expired cookie with the same attributes — Go writes `MaxAge: -1`,
    /// which `net/http` renders as `Max-Age=0`; Javalin treats a negative
    /// max-age as "unset", so the zero is written explicitly. In secure mode
    /// a leftover pre-hardening `fc_session` (set before the switch to the
    /// `__Host-` prefix) is expired too, so it never lingers in the browser
    /// after the deploy (`docs/spec/cookie-hardening.md` §3).
    public void clear(Exchange ctx) {
        // Written by hand: Jetty renders a zero max-age as an `Expires=` in the past;
        // Go's net/http writes `Max-Age=0`, and the wire should read the same (parity S2).
        ctx.header("Set-Cookie", name() + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
        if (secure) {
            ctx.addHeader("Set-Cookie", INSECURE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        }
    }
}

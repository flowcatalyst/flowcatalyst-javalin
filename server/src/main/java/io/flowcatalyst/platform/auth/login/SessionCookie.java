package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;

/// The `fc_session` cookie's attributes (`docs/spec/auth-core.md` §6.1,
/// "Cookie attributes"): `Path=/`, `HttpOnly`, `SameSite=Lax`, `Max-Age`
/// 24 h, `Secure` unless test headers are allowed (a local HTTP dev box).
public final class SessionCookie {

    public static final String NAME = Authenticator.SESSION_COOKIE;
    public static final int MAX_AGE_SECONDS = (int) TokenIssuer.SESSION_TTL_SECONDS;

    private final boolean secure;

    /// @param secure `!FC_AUTH_ALLOW_TEST_HEADERS` (Go `CookieSecure`)
    public SessionCookie(boolean secure) {
        this.secure = secure;
    }

    public boolean secure() {
        return secure;
    }

    public void set(Context ctx, String token) {
        ctx.cookie(new Cookie(NAME, token, "/", MAX_AGE_SECONDS, secure, true, null, SameSite.LAX));
    }

    /// An expired cookie with the same attributes — Go writes `MaxAge: -1`,
    /// which `net/http` renders as `Max-Age=0`; Javalin treats a negative
    /// max-age as "unset", so the zero is written explicitly.
    public void clear(Context ctx) {
        // Written by hand: Jetty renders a zero max-age as an `Expires=` in the past;
        // Go's net/http writes `Max-Age=0`, and the wire should read the same (parity S2).
        ctx.header("Set-Cookie", NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
    }
}

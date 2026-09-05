package io.flowcatalyst.platform.auth.mfa;

import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;

import java.time.Duration;

/// The remember-device cookie (`docs/spec/auth-identity.md` §6.7):
/// `__Host-fc_td` when cookies are secure, `fc_td` otherwise; `Path=/`,
/// `HttpOnly`, `SameSite=Strict`. The login decision reads the same name.
public final class TrustedDeviceCookie {

    public static final String SECURE_NAME = "__Host-fc_td";
    public static final String INSECURE_NAME = "fc_td";

    private final boolean secure;

    public TrustedDeviceCookie(boolean secure) {
        this.secure = secure;
    }

    public String name() {
        return secure ? SECURE_NAME : INSECURE_NAME;
    }

    public String read(Context ctx) {
        String v = ctx.cookie(name());
        return v == null ? "" : v;
    }

    public void set(Context ctx, String rawToken, Duration ttl) {
        ctx.cookie(new Cookie(name(), rawToken, "/", (int) ttl.toSeconds(), secure, true, null, SameSite.STRICT));
    }

    public void clear(Context ctx) {
        ctx.cookie(new Cookie(name(), "", "/", 0, secure, true, null, SameSite.STRICT));
    }
}

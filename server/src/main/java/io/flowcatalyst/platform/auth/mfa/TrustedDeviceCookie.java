package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.HttpCookie;

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

    public String read(Exchange ctx) {
        String v = ctx.cookie(name());
        return v == null ? "" : v;
    }

    public void set(Exchange ctx, String rawToken, Duration ttl) {
        ctx.cookie(new HttpCookie(name(), rawToken, "/", (int) ttl.toSeconds(), true, secure, HttpCookie.SameSite.STRICT));
    }

    public void clear(Exchange ctx) {
        ctx.cookie(new HttpCookie(name(), "", "/", 0, true, secure, HttpCookie.SameSite.STRICT));
    }
}

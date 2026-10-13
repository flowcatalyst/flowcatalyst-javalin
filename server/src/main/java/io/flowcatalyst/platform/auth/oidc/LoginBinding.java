package io.flowcatalyst.platform.auth.oidc;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.HttpCookie;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// Browser binding of an OIDC login state (SSO login CSRF; Go
/// `bridge/login_binding.go`, F16 follow-up).
///
/// The login state lives in the database, so on its own a callback URL
/// (state + code) completes in any browser: someone with an account at any
/// mapped identity provider could start a login, sign in at the IdP, stop
/// before the callback and send the link to a victim, whose browser would be
/// signed in to the attacker's account. Both starts (`GET /auth/oidc/login`,
/// `GET /portal/auth/oidc/login`) therefore set a cookie in the browser that
/// started the login, and the callback requires it.
///
/// One cookie per login (`fc_sso_<16 hex of sha256(state)>`, value the state),
/// so two sign-ins in two tabs do not overwrite each other. When secure (the
/// session cookie's decision) the name takes the `__Host-` prefix: the browser
/// then accepts it only with `Secure`, `Path=/` and no `Domain`, so a sibling
/// subdomain cannot plant one. `SameSite=Lax`: the IdP's redirect back is a
/// top-level GET navigation, on which Lax cookies are sent. `HttpOnly`,
/// 10 minutes (the login state's lifetime).
public final class LoginBinding {

    /// The login state's lifetime ([LoginState]).
    static final int TTL_SECONDS = 10 * 60;

    private final boolean secure;

    public LoginBinding(boolean secure) {
        this.secure = secure;
    }

    /// The binding cookie's name for `state`.
    String cookieName(String state) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            String name = "fc_sso_" + HexFormat.of().formatHex(sum, 0, 8);
            return secure ? "__Host-" + name : name;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Sets the binding cookie for `state` on the start's response.
    public void bind(Exchange ctx, String state) {
        ctx.cookie(new HttpCookie(cookieName(state), state, "/", TTL_SECONDS, true, secure, HttpCookie.SameSite.LAX));
    }

    /// Whether the request carries the binding cookie for `state` (its value
    /// compared in constant time).
    boolean boundToBrowser(Exchange ctx, String state) {
        String value = ctx.cookie(cookieName(state));
        return value != null && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8));
    }

    /// Expires the binding cookie for `state`, with the same attributes.
    void clear(Exchange ctx, String state) {
        ctx.addHeader("Set-Cookie", cookieName(state) + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
    }
}

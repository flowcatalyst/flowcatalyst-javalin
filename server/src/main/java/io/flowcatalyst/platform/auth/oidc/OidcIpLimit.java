package io.flowcatalyst.platform.auth.oidc;

import io.flowcatalyst.platform.auth.login.ClientIp;
import io.flowcatalyst.platform.auth.ratelimit.Governor;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.Map;
import java.util.Objects;

/// The per-instance, per-IP token bucket in front of `/auth/oidc/*` and
/// `/portal/*` (`docs/spec/auth-identity.md` §4.11; `FC_OIDC_RATE_PER_MIN`
/// 60, `FC_OIDC_BURST` 30): 429 in the platform envelope with
/// `Retry-After`. No client address ⇒ pass.
public final class OidcIpLimit {

    private OidcIpLimit() {
    }

    public static void register(JavalinDefaultRoutingApi routes, Governor governor) {
        Objects.requireNonNull(governor, "governor");
        for (String prefix : new String[] {"/auth/oidc/*", "/portal/*"}) {
            routes.before(prefix, ctx -> {
                String ip = ClientIp.of(ctx);
                if (ip == null || ip.isBlank()) {
                    return;
                }
                var check = governor.check(ip);
                if (!check.ok()) {
                    ctx.header("Retry-After", Long.toString(check.retryAfterSecs()));
                    HttpError.write(ctx, 429, "TOO_MANY_REQUESTS", "Too many authentication requests", Map.of());
                    ctx.skipRemainingHandlers();
                }
            });
        }
    }
}

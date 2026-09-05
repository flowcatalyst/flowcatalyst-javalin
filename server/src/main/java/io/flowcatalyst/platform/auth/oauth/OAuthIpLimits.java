package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.login.ClientIp;
import io.flowcatalyst.platform.auth.ratelimit.Governor;
import io.flowcatalyst.platform.auth.ratelimit.RateLimit;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

/// The per-IP layer in front of the provider (`docs/spec/auth-core.md`
/// §6.2 mounting; Go `GovernorMiddleware(oauthTokenIPGov)` →
/// `IPLimitMiddleware(oauth_token_ip)` on `/oauth/token`,
/// `IPLimitMiddleware(oauth_authorize_ip)` on `/oauth/authorize`). Composes
/// with the per-client layer inside the handlers: a flood from one address
/// is shed here before any client lookup. The distributed store fails open
/// (ruling C-Q23); a null governor is no local throttle.
public final class OAuthIpLimits {

    private OAuthIpLimits() {
    }

    public static void register(JavalinDefaultRoutingApi routes, OAuthState s, Governor tokenIpGovernor) {
        routes.before("/oauth/token", ctx -> {
            String ip = ClientIp.of(ctx);
            if (tokenIpGovernor != null) {
                var check = tokenIpGovernor.check(ip);
                if (!check.ok()) {
                    halt(ctx, check.retryAfterSecs());
                    return;
                }
            }
            var rej = RateLimit.enforce(s.rateLimit(), RateLimit.Bucket.OAUTH_TOKEN_IP, ip, s.policies().oauthTokenIp());
            if (rej != null) {
                halt(ctx, rej.retryAfterSecs());
            }
        });
        routes.before("/oauth/authorize", ctx -> {
            var rej = RateLimit.enforce(s.rateLimit(), RateLimit.Bucket.OAUTH_AUTHORIZE_IP, ClientIp.of(ctx), s.policies().oauthAuthorizeIp());
            if (rej != null) {
                halt(ctx, rej.retryAfterSecs());
            }
        });
    }

    private static void halt(Context ctx, long retryAfterSecs) {
        OAuthError.writeRateLimited(ctx, retryAfterSecs, "rate limit exceeded");
        ctx.skipRemainingHandlers();
    }
}

package io.flowcatalyst.platform.shared.http;

import io.javalin.config.JavalinConfig;

/// Response conventions every API listener shares — production (`Server`)
/// and the test harness (`TestHttp`) install the same ones, so a test sees
/// the wire a client sees.
///
/// - **A response without a body carries no `Content-Type`** (a 204, or a
///   200 whose handler wrote nothing, like `/oauth/revoke`). Javalin stamps its default
///   `text/plain` on every response before a handler runs; a bodiless
///   status keeps it unless something removes it. Go's `net/http` sends
///   nothing on a 204, and the parity harness's first run (S0) flagged the
///   difference on every update/delete route.
public final class ResponseDefaults {

    private ResponseDefaults() {
    }

    public static void register(JavalinConfig cfg) {
        cfg.routes.after(ctx -> {
            // No body (a 204, or a 200 whose handler set nothing — /oauth/revoke) → no
            // Content-Type; Go's net/http sends none when nothing was written.
            if (ctx.statusCode() == 204 || ctx.resultInputStream() == null) ctx.res().setContentType(null);
        });
    }
}

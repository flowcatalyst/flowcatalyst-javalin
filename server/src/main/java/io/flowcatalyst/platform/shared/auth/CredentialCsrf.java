package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/// The sign-in guard (login CSRF; Go `middleware.CredentialCSRF`, F16
/// follow-up): the public routes that sign a browser in or set its
/// credentials. Without it a page on another site could post the attacker's
/// credentials as a `text/plain` form and the answer would sign the victim's
/// browser in to the attacker's account. A `before` filter over the exact
/// paths in [#ROUTES], for every unsafe request, authenticated or not:
///
///  1. Provenance ([Provenance]): a request a browser marks as from another
///     origin is **403 `CROSS_ORIGIN_REQUEST`** (logged). A request with
///     **neither** `Sec-Fetch-Site` nor `Origin` passes (not a browser), the
///     deliberate difference from [SessionCsrf].
///  2. JSON only, for every caller: a body that is not `application/json` or
///     `application/*+json` (none at all included) is **415
///     `UNSUPPORTED_MEDIA_TYPE`**. A cross-origin page cannot send JSON without
///     a CORS preflight; this alone closes the attack, provenance is the
///     second wall (an allow-listed CORS origin, a same-site sibling).
///
/// Every other public unsafe route is decided in
/// `io.flowcatalyst.server.CredentialCsrfIntegrationTest`, which fails on a
/// route with no decision.
public final class CredentialCsrf implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialCsrf.class);

    /// The guarded routes (all `POST`): they mint `fc_session` (login, 2FA
    /// verify/confirm, passkey sign-in, an INVITE reset confirm), are steps of
    /// that flow (2FA begin/challenge: mail, factor secrets), set or request a
    /// credential (password reset/setup), or are the portal's password plane
    /// (one registration in Go, guarded together).
    public static final Set<String> ROUTES = Set.of(
            "/auth/login",
            "/auth/2fa/verify",
            "/auth/2fa/challenge/email",
            "/auth/2fa/enroll/totp/begin",
            "/auth/2fa/enroll/totp/confirm",
            "/auth/2fa/enroll/email/begin",
            "/auth/2fa/enroll/email/confirm",
            "/auth/password-reset/request",
            "/auth/password-reset/confirm",
            "/auth/password-setup/request",
            "/auth/webauthn/authenticate/begin",
            "/auth/webauthn/authenticate/complete",
            "/portal/auth/check-domain",
            "/portal/auth/login",
            "/portal/auth/password-reset");

    static final String CROSS_ORIGIN_MESSAGE = "This request was refused because it did not come from this site. "
            + "Sign-in and credential requests must be sent by the platform's own pages.";
    static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "This endpoint accepts only a JSON body (Content-Type: application/json).";

    private final Provenance provenance;

    public CredentialCsrf(List<String> trustedOrigins) {
        this.provenance = new Provenance(trustedOrigins);
    }

    @Override
    public void handle(Exchange ctx) {
        if (SessionCsrf.safeMethod(ctx.method())) return;
        String path = ctx.path();
        if (!ROUTES.contains(path)) return;
        String secFetchSite = ctx.header("Sec-Fetch-Site");
        String origin = ctx.header("Origin");
        String refusal = provenance.check(secFetchSite, origin, ctx.host(), false);
        if (refusal != null) {
            LOG.atWarn().setMessage("sign-in request refused: not from this origin")
                    .addKeyValue("method", ctx.method())
                    .addKeyValue("path", path)
                    .addKeyValue("origin", origin)
                    .addKeyValue("secFetchSite", secFetchSite)
                    .addKeyValue("err", refusal)
                    .log();
            SessionCsrf.refuse(ctx, 403, SessionCsrf.CROSS_ORIGIN_REQUEST, CROSS_ORIGIN_MESSAGE);
            return;
        }
        if (SessionCsrf.hasBody(ctx) && !SessionCsrf.isJsonMediaType(ctx.header("Content-Type"))) {
            SessionCsrf.refuse(ctx, 415, SessionCsrf.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_MESSAGE);
        }
    }
}

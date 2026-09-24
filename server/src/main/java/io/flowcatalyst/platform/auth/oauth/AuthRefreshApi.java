package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// `POST /auth/refresh` (`docs/spec/auth-core.md` §6.1 A6; Go
/// `authapi.Refresh`): the SPA's own refresh, outside any OAuth client.
/// The rotated token always mints an **API** token with the principal's
/// full authority — no scope narrowing, the ceiling re-derived from the
/// current principal — and refuses a token that was issued to an OAuth
/// client (that one belongs to `/oauth/token`, where the client can
/// authenticate). camelCase body, the platform error envelope.
public final class AuthRefreshApi {

    private AuthRefreshApi() {
    }

    public static void register(Routes routes, OAuthState s) {
        // Group.OIDC (admission.md §11.7 part B follow-up): verifies and rotates a
        // refresh token, exactly the grant /oauth/token's refresh_token branch verifies.
        routes.in(Group.OIDC).post("/auth/refresh", ctx -> refresh(ctx, s));
    }

    static void refresh(Exchange ctx, OAuthState s) {
        String raw;
        try {
            JsonNode body = Json.MAPPER.readTree(ctx.body());
            raw = body.path("refreshToken").asString("");
        } catch (RuntimeException e) {
            HttpError.write(ctx, 400, "INVALID_JSON", "Invalid JSON body", Map.of());
            return;
        }
        RefreshToken stored;
        String newRaw;
        // No OAuth client here: only a token issued outside any client rotates.
        switch (s.rotation().rotate(raw, null)) {
            case Result.Ok<RefreshRotation.Rotated, RefreshRotation.Rejection>(var r) -> {
                stored = r.stored();
                newRaw = r.newRaw();
            }
            case Result.Err<RefreshRotation.Rotated, RefreshRotation.Rejection>(var why) -> {
                switch (why) {
                    case RefreshRotation.Rejection.Refused _ -> unauthenticated(ctx, "Token was not issued to this client");
                    case RefreshRotation.Rejection.Unknown _ -> unauthenticated(ctx, "Invalid or expired refresh token");
                    case RefreshRotation.Rejection.ReuseDetected _ -> unauthenticated(ctx, "Invalid or expired refresh token");
                }
                return;
            }
        }
        Optional<Principal> found = s.principals().findById(stored.principalId());
        if (found.isEmpty()) {
            unauthenticated(ctx, "Invalid or expired refresh token");
            return;
        }
        Principal p = found.get();
        if (!p.active()) {
            unauthenticated(ctx, "Account is not active");
            return;
        }
        String accessToken = s.issuer().accessToken(p, TokenIssuer.Authority.full(p, List.of(), s.labels()), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", accessToken);
        out.put("tokenType", "Bearer");
        out.put("expiresIn", s.issuer().config().accessTtlSeconds());
        out.put("refreshToken", newRaw);
        ctx.status(200).header("Cache-Control", "no-store").json(out);
    }

    /// Go's `writeUnauthorized` on this handler: the login surface's own 401
    /// envelope (`{"code":"UNAUTHENTICATED"}`, auth-core §5 row 2) with the
    /// cookie realm — not the platform `error` key. Parity S2.
    private static void unauthenticated(Exchange ctx, String message) {
        ctx.header("WWW-Authenticate", "Cookie realm=\"fc_session\"");
        HttpError.writeLoginSurface(ctx, 401, "UNAUTHENTICATED", message);
    }
}

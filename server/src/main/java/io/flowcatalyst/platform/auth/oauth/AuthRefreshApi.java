package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.grant.RefreshRotation;
import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
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

    public static void register(JavalinDefaultRoutingApi routes, OAuthState s) {
        routes.post("/auth/refresh", ctx -> refresh(ctx, s));
    }

    static void refresh(Context ctx, OAuthState s) {
        String raw;
        try {
            JsonNode body = Json.MAPPER.readTree(ctx.body());
            raw = body.path("refreshToken").asString("");
        } catch (RuntimeException e) {
            HttpError.write(ctx, 400, "INVALID_JSON", "Invalid JSON body", Map.of());
            return;
        }
        RefreshRotation.Result result;
        try {
            result = s.rotation().rotate(raw, stored -> stored.oauthClientId() != null ? "Token was not issued to this client" : null);
        } catch (RefreshRotation.NotAuthorized e) {
            unauthenticated(ctx, "Token was not issued to this client");
            return;
        }
        if (result.stored().isEmpty()) {
            unauthenticated(ctx, "Invalid or expired refresh token");
            return;
        }
        RefreshToken stored = result.stored().get();
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
        result.newRaw().ifPresent(r -> out.put("refreshToken", r));
        ctx.status(200).header("Cache-Control", "no-store").json(out);
    }

    /// Go's `writeUnauthorized` on this handler: the login surface's own 401
    /// envelope (`{"code":"UNAUTHENTICATED"}`, auth-core §5 row 2) with the
    /// cookie realm — not the platform `error` key. Parity S2.
    private static void unauthenticated(Context ctx, String message) {
        ctx.header("WWW-Authenticate", "Cookie realm=\"fc_session\"");
        HttpError.writeLoginSurface(ctx, 401, "UNAUTHENTICATED", message);
    }
}

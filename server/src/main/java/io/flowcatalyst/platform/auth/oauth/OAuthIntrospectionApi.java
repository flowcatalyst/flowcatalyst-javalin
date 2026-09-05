package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.auth.grant.RefreshToken;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// `POST /oauth/introspect` (RFC 7662) and `POST /oauth/revoke` (RFC 7009)
/// (`docs/spec/auth-core.md` §6.2 O3/O4; Go `Introspect`, `Revoke`). The
/// caller authenticates with any valid platform access token **or** client
/// credentials. Introspection's `client_id` is the token's `azp` — the
/// OAuth client that minted it (ruling C-Q26). Revocation only ever revokes
/// a refresh token (ruling C-Q28) and answers 200 whatever it found.
/// Neither response carries the token endpoint's no-store headers.
public final class OAuthIntrospectionApi {

    private OAuthIntrospectionApi() {
    }

    public static void register(JavalinDefaultRoutingApi routes, OAuthState s) {
        routes.post("/oauth/introspect", ctx -> introspect(ctx, s));
        routes.post("/oauth/revoke", ctx -> revoke(ctx, s));
    }

    static void introspect(Context ctx, OAuthState s) {
        OAuthError auth = authenticateClientOrBearer(s, ctx);
        if (auth != null) {
            auth.write(ctx);
            return;
        }
        Optional<AccessTokenReader.Read> read = s.tokens().read(OAuthTokenApi.form(ctx, "token"));
        Map<String, Object> body = new LinkedHashMap<>();
        if (read.isEmpty()) {
            body.put("active", false);
            ctx.status(200).json(body);
            return;
        }
        TokenClaims c = read.get().claims();
        body.put("active", true);
        body.put("sub", c.subject());
        if (c.permissions() != null && !c.permissions().isEmpty()) {
            body.put("scope", String.join(" ", c.permissions()));
        }
        put(body, "tier", c.tier());
        put(body, "client_id", read.get().azp());
        put(body, "email", c.email());
        put(body, "name", c.name());
        put(body, "type", c.principalType());
        if (read.get().expiresAt() != null) {
            body.put("exp", read.get().expiresAt().getEpochSecond());
        }
        if (c.issuedAt() != null) {
            body.put("iat", c.issuedAt().getEpochSecond());
        }
        put(body, "iss", read.get().issuer());
        body.put("token_type", "Bearer");
        ctx.status(200).json(body);
    }

    static void revoke(Context ctx, OAuthState s) {
        OAuthError auth = authenticateClientOrBearer(s, ctx);
        if (auth != null) {
            auth.write(ctx);
            return;
        }
        String token = OAuthTokenApi.form(ctx, "token");
        if (!token.isEmpty()) {
            try {
                s.grants().revokeByHash(RefreshToken.hash(token));
            } catch (RuntimeException e) {
                // Best-effort by RFC 7009; the answer is 200 regardless.
            }
        }
        ctx.status(200).result("");
    }

    /// A Bearer header, when present, must verify; otherwise the body's
    /// client credentials (or Basic) must authenticate.
    static OAuthError authenticateClientOrBearer(OAuthState s, Context ctx) {
        String bearer = AccessTokenReader.bearer(ctx.header("Authorization"));
        if (bearer != null) {
            return s.tokens().read(bearer).isPresent() ? null : OAuthError.invalidToken("Token is invalid or expired");
        }
        var result = ClientAuthentication.authenticateClient(s, ctx, OAuthTokenApi.form(ctx, "client_id"), OAuthTokenApi.form(ctx, "client_secret"));
        return result.failed() ? result.error() : null;
    }

    private static void put(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isEmpty()) {
            body.put(key, value);
        }
    }
}

package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.http.Exchange;

import java.util.LinkedHashMap;
import java.util.Map;

/// An RFC 6749 §5.2 error: `{error, error_description?}` — **not** the
/// platform envelope — with the token endpoint's no-store headers (Go
/// `oauthError`, `writeOAuthError`). Every token-endpoint error is 400
/// except `invalid_client`, which is 401 (ruling A-15), and the per-client
/// 429 `rate_limit_exceeded` (ruling C-Q27 puts authorize on this shape too).
///
/// @param status      HTTP status
/// @param code        the `error` value
/// @param description the `error_description`; blank ⇒ omitted
public record OAuthError(int status, String code, String description) {

    public static OAuthError of(int status, String code, String description) {
        return new OAuthError(status, code, description);
    }

    public static OAuthError invalidRequest(String description) {
        return new OAuthError(400, "invalid_request", description);
    }

    public static OAuthError invalidGrant(String description) {
        return new OAuthError(400, "invalid_grant", description);
    }

    public static OAuthError invalidClient(String description) {
        return new OAuthError(401, "invalid_client", description);
    }

    public static OAuthError unauthorizedClient(int status, String description) {
        return new OAuthError(status, "unauthorized_client", description);
    }

    public static OAuthError invalidScope(String description) {
        return new OAuthError(400, "invalid_scope", description);
    }

    public static OAuthError invalidToken(String description) {
        return new OAuthError(401, "invalid_token", description);
    }

    public static OAuthError serverError(String description) {
        return new OAuthError(500, "server_error", description);
    }

    /// `429 rate_limit_exceeded` with `Retry-After`.
    public static void writeRateLimited(Exchange ctx, long retryAfterSecs, String description) {
        ctx.header("Retry-After", Long.toString(Math.max(1, retryAfterSecs)));
        new OAuthError(429, "rate_limit_exceeded", description).write(ctx);
    }

    /// The envelope without the token endpoint's cache headers: Go sets
    /// `Cache-Control: no-store` / `Pragma: no-cache` only in `token.go`
    /// (auth-core §5); the bridge's session-end and the portal authorize
    /// write the plain body (parity S3).
    public void writePlain(Exchange ctx) {
        ctx.status(status).contentType("application/json").result(Json.writeLine(body()));
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        if (description != null && !description.isEmpty()) {
            body.put("error_description", description);
        }
        return body;
    }

    public void write(Exchange ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        if (description != null && !description.isEmpty()) {
            body.put("error_description", description);
        }
        ctx.status(status)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .contentType("application/json")
                .result(Json.writeLine(body));
    }
}

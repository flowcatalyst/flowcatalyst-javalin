package io.flowcatalyst.platform.serviceaccount;

import java.util.Objects;

/// How the platform authenticates outbound calls made on a service account's
/// behalf (spec §2.1). `authType` is required; every other field is optional
/// and absent means absent — never `""` inside the JVM.
///
/// Only [#token] and [#signingSecret] have a backing column
/// (`wh_auth_token_ref` / `wh_signing_secret_ref`, encrypted at rest by
/// [ServiceAccountRepository]); `username`, `password`, `headerName` and
/// `signatureHeader` are carried on the wire and on this record for parity
/// with the Go DTO but are never persisted — the schema has no columns for
/// them, matching Go's `repository.go` exactly (spec §11).
///
/// The entity always carries plaintext in memory; the encrypted-at-rest
/// boundary is the repository's job alone.
///
/// @param authType         `NONE` \| `BEARER_TOKEN` \| `BASIC_AUTH` \| `API_KEY` \| `HMAC_SIGNATURE`
/// @param token            bearer token, plaintext
/// @param username         basic-auth username (never persisted)
/// @param password         basic-auth password (never persisted)
/// @param headerName       API-key header name (never persisted)
/// @param signingSecret    HMAC signing secret, plaintext
/// @param signingAlgorithm HMAC algorithm name
/// @param signatureHeader  HMAC signature header name (never persisted)
public record WebhookCredentials(
        WebhookAuthType authType,
        String token,
        String username,
        String password,
        String headerName,
        String signingSecret,
        String signingAlgorithm,
        String signatureHeader) {

    public WebhookCredentials {
        Objects.requireNonNull(authType, "authType");
    }

    private static final WebhookCredentials NONE = new WebhookCredentials(WebhookAuthType.NONE, null, null, null, null, null, null, null);

    /// No credentials.
    public static WebhookCredentials none() {
        return NONE;
    }

    /// A bearer token plus an HMAC signing secret — the shape create-with-credentials
    /// and application provisioning both mint (spec §4.1).
    public static WebhookCredentials bearer(String token, String signingSecret) {
        return new WebhookCredentials(WebhookAuthType.BEARER_TOKEN, token, null, null, null, signingSecret, null, null);
    }

    /// Rotates the bearer token; forces `authType` to `BEARER_TOKEN` (spec §4.6, matches Go).
    public WebhookCredentials withToken(String newToken) {
        return new WebhookCredentials(WebhookAuthType.BEARER_TOKEN, newToken, username, password, headerName,
                signingSecret, signingAlgorithm, signatureHeader);
    }

    /// Rotates the signing secret only; `authType` is left as-is (spec §4.6, matches Go).
    public WebhookCredentials withSigningSecret(String newSigningSecret) {
        return new WebhookCredentials(authType, token, username, password, headerName,
                newSigningSecret, signingAlgorithm, signatureHeader);
    }

    @Override
    public String toString() {
        return "WebhookCredentials[authType=" + authType + ", token=" + mask(token) + ", signingSecret=" + mask(signingSecret) + "]";
    }

    private static String mask(String secret) {
        return secret == null ? "null" : "***";
    }
}

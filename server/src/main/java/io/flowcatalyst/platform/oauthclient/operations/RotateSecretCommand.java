package io.flowcatalyst.platform.oauthclient.operations;

/// `RotateOAuthClientSecret`'s command (A-22, `docs/improvements.md`).
/// `graceSeconds`: `null` takes the 24h default; `0` is an immediate
/// cutover; negative is rejected (`GRACE_INVALID`).
public record RotateSecretCommand(String id, Long graceSeconds) {
}

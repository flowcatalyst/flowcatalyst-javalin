package io.flowcatalyst.platform.oauthclient.operations;

/// `RevokeOAuthClientPreviousSecret`'s command (A-22, `docs/improvements.md`).
public record RevokeOAuthClientPreviousSecretCommand(String id) {
}

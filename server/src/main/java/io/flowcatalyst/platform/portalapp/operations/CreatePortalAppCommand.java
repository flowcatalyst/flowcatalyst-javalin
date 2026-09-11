package io.flowcatalyst.platform.portalapp.operations;

/// `CreatePortalApp`'s command (spec `portal-apps.md` §3.3): the
/// single-aggregate create, used internally and by tests — the HTTP API
/// always runs [CreatePortalAppWithOAuthClient] (§3.4) instead.
public record CreatePortalAppCommand(String clientId, String code, String name, String description) {
}

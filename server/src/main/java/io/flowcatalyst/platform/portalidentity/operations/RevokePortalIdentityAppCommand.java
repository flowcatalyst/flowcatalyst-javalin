package io.flowcatalyst.platform.portalidentity.operations;

/// `RevokePortalIdentityApp`'s command (spec `portal-apps.md` §3.2): all
/// three fields are required (`TARGET_REQUIRED`) — the admin API's
/// `DELETE /api/portal-users/{id}/apps/{portalAppCode}` resolves the code to
/// an id before building this.
public record RevokePortalIdentityAppCommand(String clientId, String identityId, String portalAppId) {
}

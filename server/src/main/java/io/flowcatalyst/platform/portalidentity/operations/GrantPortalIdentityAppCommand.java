package io.flowcatalyst.platform.portalidentity.operations;

/// `GrantPortalIdentityApp`'s command (spec `portal-apps.md` §3.2): all
/// three fields are required (`TARGET_REQUIRED`) — the admin API's
/// `POST /api/portal-users/{id}/apps` resolves `portalAppCode` to an id
/// before building this.
public record GrantPortalIdentityAppCommand(String clientId, String identityId, String portalAppId) {
}

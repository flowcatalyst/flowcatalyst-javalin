package io.flowcatalyst.platform.portalidentity.operations;

/// `AssignUnassignedToApp`'s command (spec `portal-apps.md` §3.2a): both
/// fields required (`TARGET_REQUIRED`).
public record AssignUnassignedToAppCommand(String clientId, String portalAppId) {
}

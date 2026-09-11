package io.flowcatalyst.platform.portalapp.operations;

/// `DeletePortalApp`'s command (spec `portal-apps.md` §3.6): `id` required;
/// `clientId`, when given, must match the app's owner or the app is treated
/// as not found (cross-client hidden, [Access]).
public record DeletePortalAppCommand(String clientId, String id) {
}

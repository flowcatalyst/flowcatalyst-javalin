package io.flowcatalyst.platform.portalapp.operations;

/// `UpdatePortalApp`'s command (spec `portal-apps.md` §3.5): `id` required;
/// `name`/`description`/`active` optional — `null` = untouched, matching
/// [io.flowcatalyst.platform.portalapp.PortalApp#update]. `code` never
/// changes — there is no parameter for it. `clientId`, when given, must
/// match the app's owner or the app is treated as not found (cross-client
/// hidden, [Access]).
public record UpdatePortalAppCommand(String clientId, String id, String name, String description, Boolean active) {
}

package io.flowcatalyst.platform.portalidentity.operations;

/// `DeletePortalIdentity`'s command. `clientId` is the caller's tenant
/// (from the admin route's `?clientId=`); when present and it disagrees
/// with the row's, the delete 404s exactly like a missing id (spec §5.7:
/// "cross-client hidden").
public record DeleteCommand(String clientId, String id) {
}

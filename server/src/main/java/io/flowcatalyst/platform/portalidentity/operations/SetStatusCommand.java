package io.flowcatalyst.platform.portalidentity.operations;

/// `SetPortalIdentityStatus`'s command (spec `auth-identity.md` §5.7): the
/// target is `id` (the admin API's `activate`/`deactivate`/`{id}` shape,
/// with `clientId` supplied too so the cross-client check applies) **or**
/// `clientId` + `email` when there is no id yet to hand the caller.
public record SetStatusCommand(String id, String clientId, String email, String status) {
}

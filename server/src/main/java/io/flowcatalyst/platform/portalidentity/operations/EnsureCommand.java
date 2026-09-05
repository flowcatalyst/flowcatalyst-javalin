package io.flowcatalyst.platform.portalidentity.operations;

/// `EnsurePortalIdentity`'s command (spec `auth-identity.md` §5.7): `name`
/// is optional (`null`/blank ⇒ untouched on an existing row); `source` is
/// the literal string `"JIT"` for the portal-SSO callback sink (wired by
/// another unit) or anything else (including `null`, the admin API's case)
/// for `"INVITE"` — see [io.flowcatalyst.platform.portalidentity.PortalIdentitySource].
public record EnsureCommand(String clientId, String email, String name, String source) {
}

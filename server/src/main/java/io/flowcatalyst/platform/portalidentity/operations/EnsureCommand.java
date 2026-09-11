package io.flowcatalyst.platform.portalidentity.operations;

/// `EnsurePortalIdentity`'s command (spec `auth-identity.md` §5.7,
/// `portal-apps.md` §3.1): `name` is optional (`null`/blank ⇒ untouched on
/// an existing row); `source` is the literal string `"JIT"` for the
/// portal-SSO callback sink (wired by another unit) or anything else
/// (including `null`, the admin API's case) for `"INVITE"` — see
/// [io.flowcatalyst.platform.portalidentity.PortalIdentitySource].
/// `portalAppId` is `null` ⇔ none — when given, the identity is granted
/// that app (spec `portal-apps.md` §3.1 step 5).
public record EnsureCommand(String clientId, String email, String name, String source, String portalAppId) {
}

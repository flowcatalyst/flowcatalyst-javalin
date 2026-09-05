package io.flowcatalyst.platform.portalidentity;

/// How a portal identity's row first came to exist (spec `auth-identity.md`
/// §3.3, §5.7): `INVITE` — an admin called `POST /api/portal-users`;
/// `JIT` — the portal SSO callback sink provisioned it on first federated
/// sign-in (§5.6, wired by another unit). `Ensure`'s rule (§5.7): any
/// command source other than the literal `"JIT"` becomes `INVITE` — there
/// is no wire validation error for a garbage value, so this has no `parse`
/// that throws; the one caller of a raw string is `Ensure`'s execute phase.
public enum PortalIdentitySource {
    INVITE,
    JIT
}

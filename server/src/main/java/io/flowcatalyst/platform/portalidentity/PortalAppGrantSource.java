package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// How a [PortalAppGrant] came to exist (spec `portal-apps.md` §2.2, §3.1,
/// §3.2): `INVITE` — an admin's `ensurePortalUser` call; `JIT` — the
/// portal-SSO callback's first login; `ADMIN` — `GrantApp`. Distinct from
/// [PortalIdentitySource], which is the *identity's* own origin, not a
/// per-app grant's.
public enum PortalAppGrantSource {
    INVITE,
    JIT,
    ADMIN;

    /// The one reader, wire and storage alike: the stored value is always
    /// one we wrote (CONVENTIONS §2).
    ///
    /// @throws UseCaseException validation `SOURCE_INVALID`
    public static PortalAppGrantSource parse(String value) {
        if (value != null) {
            for (PortalAppGrantSource s : values()) {
                if (s.name().equals(value)) {
                    return s;
                }
            }
        }
        throw UseCaseException.validation("SOURCE_INVALID", "source must be INVITE, JIT or ADMIN");
    }
}

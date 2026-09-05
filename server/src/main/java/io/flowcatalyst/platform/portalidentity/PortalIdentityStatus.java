package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.sdk.usecase.UseCaseException;

/// A portal identity's lifecycle status (spec `auth-identity.md` §3.3,
/// §11.8): `ACTIVE` signs in and redeems SSO/token exchanges normally;
/// `DISABLED` blocks password login (uniform 401), SSO (`access_denied`)
/// and token redemption (`invalid_grant`) while keeping the row (and any
/// password) intact for a later `activate`.
public enum PortalIdentityStatus {
    ACTIVE,
    DISABLED;

    /// The one reader, wire and storage alike: the stored value is always
    /// one we wrote, so there is no separate lenient form (CONVENTIONS §2).
    ///
    /// @throws UseCaseException validation `STATUS_INVALID`
    public static PortalIdentityStatus parse(String value) {
        if (value != null) {
            for (PortalIdentityStatus s : values()) {
                if (s.name().equals(value)) {
                    return s;
                }
            }
        }
        throw UseCaseException.validation("STATUS_INVALID", "status must be ACTIVE or DISABLED");
    }
}

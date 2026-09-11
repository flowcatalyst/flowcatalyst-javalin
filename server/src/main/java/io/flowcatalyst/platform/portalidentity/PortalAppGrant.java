package io.flowcatalyst.platform.portalidentity;

import java.time.Instant;
import java.util.Objects;

/// One portal identity's access to one portal app (spec `portal-apps.md`
/// §2.2): persisted in `portal_identity_apps`, synced with the identity by
/// [PortalIdentityRepository#persist] to exactly the set the aggregate
/// carries.
///
/// @param appId     the granted [io.flowcatalyst.platform.portalapp.PortalApp]'s id
/// @param source    how the grant came to exist
/// @param grantedAt when the grant was made
public record PortalAppGrant(String appId, PortalAppGrantSource source, Instant grantedAt) {

    public PortalAppGrant {
        Objects.requireNonNull(appId, "appId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(grantedAt, "grantedAt");
    }
}

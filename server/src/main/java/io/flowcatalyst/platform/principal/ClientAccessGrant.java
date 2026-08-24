package io.flowcatalyst.platform.principal;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// A PARTNER user's access to one client (`iam_client_access_grants`,
/// spec §1). Its own aggregate root — grant and revoke each persist one
/// row and emit one event — with `(principalId, clientId)` unique.
///
/// @param id          `gnt_…` TSID
/// @param principalId the PARTNER user
/// @param clientId    the client reached
/// @param grantedBy   acting principal
/// @param grantedAt   when
/// @param createdAt   row creation
/// @param updatedAt   last write
public record ClientAccessGrant(
        String id,
        String principalId,
        String clientId,
        String grantedBy,
        Instant grantedAt,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public ClientAccessGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(grantedBy, "grantedBy");
        Objects.requireNonNull(grantedAt, "grantedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh grant, stamped now.
    public static ClientAccessGrant create(String principalId, String clientId, String grantedBy) {
        Instant now = Instant.now();
        return new ClientAccessGrant(EntityType.CLIENT_ACCESS_GRANT.generate(), principalId, clientId, grantedBy, now, now, now);
    }
}

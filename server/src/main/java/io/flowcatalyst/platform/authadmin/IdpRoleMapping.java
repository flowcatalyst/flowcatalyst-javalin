package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// Maps an upstream IdP role name (per [#idpType], e.g. `keycloak`, `entra`)
/// to a platform role name (spec §1). Read by IdP role sync. IdP role
/// mappings are anchor-only platform configuration with no per-resource
/// scope, and immutable once created — there is no update operation (spec §3).
///
/// @param id               `irm_…` TSID
/// @param idpType          the identity provider kind this mapping applies to;
///                         `null` only for a legacy row predating the column
///                         (spec §6 D4) — every mapping created through this
///                         API supplies it
/// @param idpRoleName      the upstream role name, unique, stored as given
/// @param platformRoleName the platform `app:role` this maps to, stored as given
/// @param createdAt        creation time
/// @param updatedAt        last change
public record IdpRoleMapping(String id, String idpType, String idpRoleName, String platformRoleName,
                             Instant createdAt, Instant updatedAt) implements HasId {

    public IdpRoleMapping {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(idpRoleName, "idpRoleName");
        Objects.requireNonNull(platformRoleName, "platformRoleName");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh mapping. `idpType`, `idpRoleName` and `platformRoleName`
    /// arrive already validated non-blank (spec §4.3).
    public static IdpRoleMapping create(String idpType, String idpRoleName, String platformRoleName) {
        Instant now = Instant.now();
        return new IdpRoleMapping(EntityType.IDP_ROLE_MAPPING.generate(), idpType, idpRoleName, platformRoleName, now, now);
    }
}

package io.flowcatalyst.platform.principal;

import java.util.Objects;

/// The federated identity of a user provisioned by an OIDC provider
/// (spec §1): read from `idp_type` + `external_idp_id`, present iff the
/// latter is set. On write it wins over [UserIdentity#provider()] /
/// [UserIdentity#externalId()] for the two IdP columns.
///
/// @param providerId the identity provider (`idp_type`); `""` when the row has an external id but no provider
/// @param externalId the subject at that provider
public record ExternalIdentity(String providerId, String externalId) {
    public ExternalIdentity {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(externalId, "externalId");
    }
}

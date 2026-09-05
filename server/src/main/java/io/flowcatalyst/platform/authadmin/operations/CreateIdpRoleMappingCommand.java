package io.flowcatalyst.platform.authadmin.operations;

/// The input DTO for [CreateIdpRoleMapping] (audit `operation` = `CreateIdpRoleMappingCommand`).
///
/// @param idpType          the identity provider kind, e.g. `keycloak`, `entra`
/// @param idpRoleName      the upstream role name, unique
/// @param platformRoleName the platform `app:role` this maps to
public record CreateIdpRoleMappingCommand(String idpType, String idpRoleName, String platformRoleName) {
}

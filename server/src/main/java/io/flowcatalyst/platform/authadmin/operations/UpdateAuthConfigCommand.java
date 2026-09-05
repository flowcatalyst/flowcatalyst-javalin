package io.flowcatalyst.platform.authadmin.operations;

import java.util.List;

/// The input DTO for [UpdateAuthConfig] (audit `operation` = `UpdateAuthConfigCommand`).
/// `emailDomain` and `configType` are not updatable here (spec §4.2). Every
/// other field is `null` = unchanged (lists: `null` = unchanged, `[]` = clear).
///
/// @param id                    the auth config
/// @param primaryClientId       `null` = unchanged
/// @param additionalClientIds   `null` = unchanged, empty = clear
/// @param grantedClientIds      `null` = unchanged, empty = clear
/// @param authProvider          `null` = unchanged; when supplied must parse (spec §4.2)
/// @param oidcIssuerUrl         `null` = unchanged
/// @param oidcClientId          `null` = unchanged
/// @param oidcMultiTenant       `null` = unchanged
/// @param oidcIssuerPattern     `null` = unchanged
/// @param oidcClientSecretRef   `null` = unchanged
public record UpdateAuthConfigCommand(
        String id,
        String primaryClientId,
        List<String> additionalClientIds,
        List<String> grantedClientIds,
        String authProvider,
        String oidcIssuerUrl,
        String oidcClientId,
        Boolean oidcMultiTenant,
        String oidcIssuerPattern,
        String oidcClientSecretRef) {
}

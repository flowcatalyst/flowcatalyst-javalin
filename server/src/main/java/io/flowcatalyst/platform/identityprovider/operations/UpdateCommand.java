package io.flowcatalyst.platform.identityprovider.operations;

import java.util.List;

/// The input DTO for [UpdateIdentityProvider] (audit `operation` =
/// `UpdateCommand`). `null` = untouched; a blank string clears its field
/// (`""` on `oidcClientSecretRef` removes the secret); `oidcClientSecretRef`
/// is already the at-rest form (spec §5).
///
/// @param id                  the provider
/// @param name                new display name (trimmed); `null` = unchanged
/// @param oidcIssuerUrl       `null` = unchanged
/// @param oidcClientId        `null` = unchanged
/// @param oidcClientSecretRef at-rest ref; `null` = unchanged; blank = clear
/// @param oidcMultiTenant     `null` = unchanged
/// @param oidcIssuerPattern   `null` = unchanged
/// @param allowedEmailDomains the **desired set** of domains routed to this provider; `null` = mappings untouched (spec §4)
/// @param mappingScope        `ANCHOR` | `CLIENT`, required whenever a listed domain has no mapping yet (owner ruling 2026-09-15, spec §4); a removal-only update needs none; has no effect on a domain's existing scope
/// @param primaryClientId     client to link on mappings that are new or not yet linked; `null` = none
/// @param syncRolesFromIdp    `null` = unchanged
/// @param allowedRoleIds      replaces the restriction; `[]` clears it; `null` = unchanged
public record UpdateCommand(
        String id,
        String name,
        String oidcIssuerUrl,
        String oidcClientId,
        String oidcClientSecretRef,
        Boolean oidcMultiTenant,
        String oidcIssuerPattern,
        List<String> allowedEmailDomains,
        String mappingScope,
        String primaryClientId,
        Boolean syncRolesFromIdp,
        List<String> allowedRoleIds) {

    public UpdateCommand {
        allowedEmailDomains = allowedEmailDomains == null ? null : List.copyOf(allowedEmailDomains);
        allowedRoleIds = allowedRoleIds == null ? null : List.copyOf(allowedRoleIds);
    }
}

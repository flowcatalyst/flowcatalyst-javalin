package io.flowcatalyst.platform.identityprovider.operations;

import java.util.List;

/// The input DTO for [CreateIdentityProvider]. The record's simple name is
/// the audit log's `operation` column, so it must stay `CreateCommand` — and
/// because the audit stores it as JSON, `oidcClientSecretRef` must already
/// be the at-rest form (spec §5), never a plaintext secret.
///
/// @param code                unique code (stored verbatim)
/// @param name                display name
/// @param type                `INTERNAL` | `OIDC` (lenient)
/// @param oidcIssuerUrl       required when `OIDC`
/// @param oidcClientId        required when `OIDC`
/// @param oidcClientSecretRef at-rest secret ref; `null` / blank = no secret
/// @param oidcMultiTenant     multi-tenant issuer flag
/// @param oidcIssuerPattern   issuer regex, optional
/// @param allowedEmailDomains domains to route to the new provider (created or claimed); `null` = none
/// @param mappingScope        `ANCHOR` | `CLIENT`, required whenever a listed domain has no mapping yet (owner ruling 2026-09-15, spec §4); has no effect on a domain's existing scope
/// @param primaryClientId     client to link on mappings that are new or not yet linked; `null` = none
/// @param syncRolesFromIdp    reconcile `IDP_SYNC` roles at login
/// @param allowedRoleIds      roles the provider may confer; `null` / empty = no restriction
/// @param allowedTenantIds    multi-tenant OIDC only: the Entra tenants the provider accepts; `null` / empty =
///                            none at the provider level, so every mapping needs its own pin (backlog item 3)
public record CreateCommand(
        String code,
        String name,
        String type,
        String oidcIssuerUrl,
        String oidcClientId,
        String oidcClientSecretRef,
        boolean oidcMultiTenant,
        String oidcIssuerPattern,
        List<String> allowedEmailDomains,
        String mappingScope,
        String primaryClientId,
        boolean syncRolesFromIdp,
        List<String> allowedRoleIds,
        List<String> allowedTenantIds) {

    public CreateCommand {
        allowedEmailDomains = allowedEmailDomains == null ? null : List.copyOf(allowedEmailDomains);
        allowedRoleIds = allowedRoleIds == null ? null : List.copyOf(allowedRoleIds);
        allowedTenantIds = allowedTenantIds == null ? null : List.copyOf(allowedTenantIds);
    }

    /// Without a provider-level tenant pin.
    public CreateCommand(String code, String name, String type, String oidcIssuerUrl, String oidcClientId,
                         String oidcClientSecretRef, boolean oidcMultiTenant, String oidcIssuerPattern,
                         List<String> allowedEmailDomains, String mappingScope, String primaryClientId,
                         boolean syncRolesFromIdp, List<String> allowedRoleIds) {
        this(code, name, type, oidcIssuerUrl, oidcClientId, oidcClientSecretRef, oidcMultiTenant, oidcIssuerPattern,
                allowedEmailDomains, mappingScope, primaryClientId, syncRolesFromIdp, allowedRoleIds, null);
    }
}

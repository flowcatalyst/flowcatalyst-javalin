package io.flowcatalyst.platform.authadmin.operations;

import java.util.List;

/// The input DTO for [CreateAuthConfig] (audit `operation` = `CreateAuthConfigCommand`).
///
/// @param emailDomain          raw domain; normalised by the operation
/// @param configType           `ANCHOR` | `PARTNER` | `CLIENT`
/// @param primaryClientId      optional
/// @param additionalClientIds  optional; `null` = none
/// @param grantedClientIds     optional; `null` = none
/// @param authProvider         `INTERNAL` | `OIDC`
/// @param oidcIssuerUrl        required when `authProvider` is `OIDC`
/// @param oidcClientId         required when `authProvider` is `OIDC`
/// @param oidcMultiTenant      whether the OIDC issuer is multi-tenant
/// @param oidcIssuerPattern    optional
/// @param oidcClientSecretRef  optional; a reference, never the secret itself
public record CreateAuthConfigCommand(
        String emailDomain,
        String configType,
        String primaryClientId,
        List<String> additionalClientIds,
        List<String> grantedClientIds,
        String authProvider,
        String oidcIssuerUrl,
        String oidcClientId,
        boolean oidcMultiTenant,
        String oidcIssuerPattern,
        String oidcClientSecretRef) {
}

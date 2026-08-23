package io.flowcatalyst.platform.emaildomainmapping.operations;

import java.util.List;

/// The input DTO for [CreateEmailDomainMapping] (audit `operation` = `CreateCommand`).
///
/// @param emailDomain           raw domain; normalised by the operation
/// @param identityProviderId    the provider the domain authenticates with
/// @param scopeType             `ANCHOR` | `PARTNER` | `CLIENT`
/// @param primaryClientId       optional; required for `PARTNER`/`CLIENT`
/// @param additionalClientIds   optional; `null` = none
/// @param grantedClientIds      optional; `null` = none
/// @param requiredOidcTenantId  optional
/// @param require2fa            default `false`
/// @param allowed2faMethods     optional; `null` = none
/// @param rememberDeviceEnabled default `false`
/// @param rememberDeviceDays    optional; `null` or `<= 0` = the domain default (spec §1)
public record CreateCommand(
        String emailDomain,
        String identityProviderId,
        String scopeType,
        String primaryClientId,
        List<String> additionalClientIds,
        List<String> grantedClientIds,
        String requiredOidcTenantId,
        boolean require2fa,
        List<String> allowed2faMethods,
        boolean rememberDeviceEnabled,
        Integer rememberDeviceDays) {
}

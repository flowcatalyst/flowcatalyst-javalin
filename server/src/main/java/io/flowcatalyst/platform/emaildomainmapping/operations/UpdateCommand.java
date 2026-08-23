package io.flowcatalyst.platform.emaildomainmapping.operations;

import java.util.List;

/// The input DTO for [UpdateEmailDomainMapping] (audit `operation` = `UpdateCommand`).
/// The domain, provider and scope are not updatable here — re-pointing a
/// domain goes through [MoveEmailDomainMappingProvider].
///
/// @param id                    the mapping
/// @param primaryClientId       replaced wholesale: `null` clears (spec §1)
/// @param additionalClientIds   `null` = unchanged, empty = clear
/// @param grantedClientIds      `null` = unchanged, empty = clear
/// @param requiredOidcTenantId  replaced wholesale: `null` clears
/// @param require2fa            `null` = unchanged
/// @param allowed2faMethods     `null` = unchanged, empty = clear
/// @param rememberDeviceEnabled `null` = unchanged
/// @param rememberDeviceDays    `null` = unchanged; any other value is stored as-is
public record UpdateCommand(
        String id,
        String primaryClientId,
        List<String> additionalClientIds,
        List<String> grantedClientIds,
        String requiredOidcTenantId,
        Boolean require2fa,
        List<String> allowed2faMethods,
        Boolean rememberDeviceEnabled,
        Integer rememberDeviceDays) {
}

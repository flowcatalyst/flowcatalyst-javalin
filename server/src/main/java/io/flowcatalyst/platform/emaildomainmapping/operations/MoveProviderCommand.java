package io.flowcatalyst.platform.emaildomainmapping.operations;

/// The input DTO for [MoveEmailDomainMappingProvider] (audit `operation` = `MoveProviderCommand`).
///
/// @param id                 the mapping
/// @param identityProviderId the target provider
public record MoveProviderCommand(String id, String identityProviderId) {
}

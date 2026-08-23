package io.flowcatalyst.platform.emaildomainmapping.operations;

/// What [MoveEmailDomainMappingProvider] reports back: the mapping, the
/// provider it left and joined, and how many OIDC-provisioned users were
/// converted back to internal auth (`0` when moving toward an OIDC
/// provider — spec §2).
public record MoveProviderResult(String mappingId, String emailDomain, String fromIdentityProviderId,
                                 String toIdentityProviderId, int usersReset) {
}

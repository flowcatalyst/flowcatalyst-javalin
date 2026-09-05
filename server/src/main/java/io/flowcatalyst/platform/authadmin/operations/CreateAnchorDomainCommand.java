package io.flowcatalyst.platform.authadmin.operations;

/// The input DTO for [CreateAnchorDomain] (audit `operation` = `CreateAnchorDomainCommand`).
///
/// @param domain raw domain; normalised by the operation
public record CreateAnchorDomainCommand(String domain) {
}

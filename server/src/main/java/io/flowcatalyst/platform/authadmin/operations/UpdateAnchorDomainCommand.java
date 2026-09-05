package io.flowcatalyst.platform.authadmin.operations;

/// The input DTO for [UpdateAnchorDomain] (audit `operation` = `UpdateAnchorDomainCommand`).
///
/// @param id     the anchor domain
/// @param domain raw domain; normalised by the operation
public record UpdateAnchorDomainCommand(String id, String domain) {
}

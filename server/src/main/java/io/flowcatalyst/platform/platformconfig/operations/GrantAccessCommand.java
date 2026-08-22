package io.flowcatalyst.platform.platformconfig.operations;

/// The input DTO for [GrantAccess] (audit `operation` = `GrantAccessCommand`).
///
/// @param applicationCode the application the grant is for
/// @param roleCode        the role the grant is for
/// @param canWrite        whether the role may also set / delete values
public record GrantAccessCommand(String applicationCode, String roleCode, boolean canWrite) {
}

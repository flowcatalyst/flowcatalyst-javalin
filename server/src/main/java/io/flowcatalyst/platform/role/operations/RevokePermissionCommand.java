package io.flowcatalyst.platform.role.operations;

/// The input DTO for [RevokePermission] (audit `operation` =
/// `RevokePermissionCommand`): the role is addressed by **name**.
public record RevokePermissionCommand(String roleName, String permission) {
}

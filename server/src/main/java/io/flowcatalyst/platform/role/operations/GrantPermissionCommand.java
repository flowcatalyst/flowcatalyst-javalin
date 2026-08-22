package io.flowcatalyst.platform.role.operations;

/// The input DTO for [GrantPermission] (audit `operation` =
/// `GrantPermissionCommand`): the role is addressed by **name**. Re-granting
/// a held permission is a no-op on the set but still emits the event, so
/// the audit trail records the admin action.
public record GrantPermissionCommand(String roleName, String permission) {
}

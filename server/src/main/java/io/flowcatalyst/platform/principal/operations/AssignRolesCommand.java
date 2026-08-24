package io.flowcatalyst.platform.principal.operations;

import java.util.List;

/// The input DTO for [AssignRoles] (audit `operation` = `AssignRolesCommand`):
/// the complete desired role set.
public record AssignRolesCommand(String userId, List<String> roles) {
    public AssignRolesCommand {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}

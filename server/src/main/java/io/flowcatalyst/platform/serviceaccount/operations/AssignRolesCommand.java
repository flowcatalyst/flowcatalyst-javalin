package io.flowcatalyst.platform.serviceaccount.operations;

import java.util.List;

/// The input DTO for [AssignRolesToServiceAccount] (audit `operation` =
/// `AssignRolesCommand`): a declarative, wholesale replacement of the linked
/// principal's role set (spec §4.5).
public record AssignRolesCommand(String serviceAccountId, List<String> roles) {

    public AssignRolesCommand {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}

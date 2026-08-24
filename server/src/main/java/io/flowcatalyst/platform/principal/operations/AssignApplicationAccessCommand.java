package io.flowcatalyst.platform.principal.operations;

import java.util.List;

/// The input DTO for [AssignApplicationAccess] (audit `operation` =
/// `AssignApplicationAccessCommand`): the complete explicit application set,
/// plus the all-applications flag (`null` = unchanged).
public record AssignApplicationAccessCommand(String userId, List<String> applicationIds, Boolean allApplications) {
    public AssignApplicationAccessCommand {
        applicationIds = applicationIds == null ? List.of() : List.copyOf(applicationIds);
    }
}

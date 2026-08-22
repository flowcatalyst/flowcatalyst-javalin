package io.flowcatalyst.platform.role.operations;

import java.util.List;

/// The input DTO for [UpdateRole] (audit `operation` = `UpdateCommand`).
/// Every field but `id` is optional: `null` = leave untouched;
/// `permissions = []` clears the set (spec §3).
public record UpdateCommand(String id, String displayName, String description, List<String> permissions,
                            Boolean clientManaged) {

    public UpdateCommand {
        permissions = permissions == null ? null : List.copyOf(permissions);
    }
}

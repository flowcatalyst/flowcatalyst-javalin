package io.flowcatalyst.platform.role.operations;

import java.util.List;

/// One role definition in a [SyncRolesCommand] batch (the camelCase wire
/// shape lives in the sdksync API layer).
///
/// @param name          bare (`hr-manager`) or already qualified (`hr:hr-manager`); lower-cased, prefix-stripped
/// @param displayName   optional; falls back to the raw `name`
/// @param description   optional
/// @param permissions   empty = keep the stored permissions of an existing role
/// @param clientManaged informational flag
public record SyncRoleInput(String name, String displayName, String description, List<String> permissions,
                            boolean clientManaged) {

    public SyncRoleInput {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}

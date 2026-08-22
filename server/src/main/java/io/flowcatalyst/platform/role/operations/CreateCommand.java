package io.flowcatalyst.platform.role.operations;

import java.util.List;

/// The input DTO for [CreateRole]. The record's simple name is the audit
/// log's `operation` column, so it must stay `CreateCommand`.
///
/// @param applicationCode first segment of the canonical name
/// @param roleName        app-local name; joined verbatim as `{applicationCode}:{roleName}`
/// @param displayName     human-readable name
/// @param description     optional
/// @param permissions     initial permission codes (de-duplicated on the aggregate)
/// @param clientManaged   informational flag
public record CreateCommand(String applicationCode, String roleName, String displayName, String description,
                            List<String> permissions, boolean clientManaged) {

    public CreateCommand {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}

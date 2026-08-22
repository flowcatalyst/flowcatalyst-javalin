package io.flowcatalyst.platform.role.operations;

import java.util.List;

/// The input DTO for [SyncRoles] (audit `operation` = `SyncRolesCommand`).
/// `applicationId` is resolved by the caller from `applicationCode`; the
/// use case authorizes against it, scopes the existing-roles lookup by it
/// and stamps it on freshly created rows. `removeUnlisted` removes the
/// application's `SDK`-sourced roles absent from `roles`; `CODE` /
/// `DATABASE` rows are never touched.
public record SyncRolesCommand(String applicationCode, String applicationId, List<SyncRoleInput> roles,
                               boolean removeUnlisted) {

    public SyncRolesCommand {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}

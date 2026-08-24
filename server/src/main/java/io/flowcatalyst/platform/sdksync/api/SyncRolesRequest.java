package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.role.operations.SyncRoleInput;
import io.flowcatalyst.platform.role.operations.SyncRolesCommand;

import java.util.List;

/// `SyncRolesRequest` (lockfile): `{roles[]: {name, displayName, description, permissions[], clientManaged}}`.
public record SyncRolesRequest(List<Input> roles) {

    public record Input(String name, String displayName, String description, List<String> permissions, Boolean clientManaged) {
        SyncRoleInput toInput() {
            return new SyncRoleInput(name, displayName, description, permissions, Boolean.TRUE.equals(clientManaged));
        }
    }

    SyncRolesCommand toCommand(String applicationCode, String applicationId, boolean removeUnlisted) {
        return new SyncRolesCommand(applicationCode, applicationId,
                roles == null ? List.of() : roles.stream().map(Input::toInput).toList(), removeUnlisted);
    }
}

package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces the supplied fields of an existing role (`null` = untouched)
/// and emits [RoleUpdated]. The name is immutable; `CODE` roles refuse
/// (spec §2).
public final class UpdateRole {

    private UpdateRole() {
    }

    public static Operation<UpdateCommand, RoleUpdated> of(RoleRepository repo) {
        return Operation.<UpdateCommand, RoleUpdated>named("UpdateRole")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.displayName() != null) {
                        UseCaseException.requireNonBlank(cmd.displayName(), "DISPLAY_NAME_REQUIRED", "displayName cannot be empty");
                    }
                })
                // Roles are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Role role = Access.byId(repo, cmd.id())
                            .update(new Role.Changes(cmd.displayName(), cmd.description(), cmd.permissions(), cmd.clientManaged()));
                    return Plan.save(role, repo, RoleUpdated.of(ec, role));
                });
    }
}

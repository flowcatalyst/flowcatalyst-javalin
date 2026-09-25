package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a role with its permission rows and emits [RoleDeleted].
/// `CODE` roles refuse (spec §2). Principal assignments are not checked —
/// `iam_principal_roles` references roles by name without a foreign key.
public final class DeleteRole {

    private DeleteRole() {
    }

    public static Operation<DeleteCommand, RoleDeleted> of(RoleRepository repo) {
        return Operation.<DeleteCommand, RoleDeleted>named("DeleteRole")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Roles are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Role role = Access.byId(repo, cmd.id()).requireDeletable();
                    // Owner ruling 2026-09-25: deleting a role strips it from everyone holding it,
                    // so it is a removal of each of its permissions.
                    RoleCeiling.requirePermissions(Auth.current(), role.permissions());
                    return Plan.delete(role, repo, RoleDeleted.of(ec, role));
                });
    }
}

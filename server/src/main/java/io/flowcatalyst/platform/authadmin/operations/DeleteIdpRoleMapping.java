package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.shared.auth.Auth;
import java.util.List;

import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.platform.authadmin.IdpRoleMappingRepository;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.IdpRoleMappingDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an IdP role mapping and emits [IdpRoleMappingDeleted] (spec §4.3).
public final class DeleteIdpRoleMapping {

    private DeleteIdpRoleMapping() {
    }

    /// @param rolePermissions for the role ceiling: removal counts (owner ruling 2026-09-25)
    public static Operation<DeleteIdpRoleMappingCommand, IdpRoleMappingDeleted> of(IdpRoleMappingRepository repo,
                                                                                   RoleCeiling.RolePermissions rolePermissions) {
        return Operation.<DeleteIdpRoleMappingCommand, IdpRoleMappingDeleted>named("DeleteIdpRoleMapping")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // IdP role mappings are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    IdpRoleMapping m = Access.idpRoleMappingById(repo, cmd.id());
                    RoleCeiling.requireRoles(Auth.current(), List.of(m.platformRoleName()), rolePermissions);
                    return Plan.delete(m, repo, IdpRoleMappingDeleted.of(ec, m));
                });
    }
}

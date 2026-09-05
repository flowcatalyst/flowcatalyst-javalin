package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.RolesAssigned;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces a user's full role set (every assignment becomes
/// `ADMIN_ASSIGNED`) and emits [RolesAssigned] (spec §2). The per-resource
/// rule (`Access.requireUserAdmin`) runs post-load; the application-scoped
/// *bounding* of what a non-anchor may name is command shaping the handler
/// performs before building the desired set (spec §5.3).
public final class AssignRoles {

    private AssignRoles() {
    }

    public static Operation<AssignRolesCommand, RolesAssigned> of(PrincipalRepository repo, RoleRepository roles) {
        return Operation.<AssignRolesCommand, RolesAssigned>named("AssignRoles")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.userId(), "USER_ID_REQUIRED", "User ID is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource rule runs post-load: Access.requireUserAdmin
                .execute((cmd, ec) -> {
                    Principal p = Access.loadUser(repo, cmd.userId());
                    // "Principal": the PUT/POST/DELETE .../roles routes all pre-load
                    // with principal(s, id) ("Principal_NOT_FOUND"), so the
                    // out-of-scope 404 must match that, not Access.loadUser's own
                    // "User" spelling above (unreachable in practice — the handler
                    // already proved the row exists).
                    Access.requireUserAdmin(p, "Principal");
                    if (!p.isUser()) {
                        throw UseCaseException.businessRule("NOT_A_USER", "Roles can only be assigned to USER type principals");
                    }
                    for (String name : cmd.roles()) {
                        if (roles.findByName(name).isEmpty()) {
                            throw UseCaseException.validation("ROLE_NOT_FOUND", "Role not found: " + name);
                        }
                    }
                    var change = p.assignRoles(cmd.roles());
                    return Plan.save(change.principal(), repo.withRoles(), RolesAssigned.of(ec, change));
                });
    }
}

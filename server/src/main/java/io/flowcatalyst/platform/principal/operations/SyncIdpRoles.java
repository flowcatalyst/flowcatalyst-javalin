package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.RolesAssigned;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces a user's `IDP_SYNC`-sourced role assignments with the set an
/// identity provider's claim resolved to, keeping every other assignment,
/// and emits [RolesAssigned] (spec §2). Every incoming role must exist.
///
/// [Operation.Authorize#publicAccess()]: this runs inside the unauthenticated
/// login bridge — a system flow reconciling a just-authenticated user's roles
/// from their IdP claims. There is no admin actor; the login flow is the gate.
public final class SyncIdpRoles {

    private SyncIdpRoles() {
    }

    public static Operation<SyncIdpRolesCommand, RolesAssigned> of(PrincipalRepository repo, RoleRepository roles) {
        return Operation.<SyncIdpRolesCommand, RolesAssigned>named("SyncIdpRoles")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.userId(), "USER_ID_REQUIRED", "User ID is required"))
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Principal p = Access.loadUser(repo, cmd.userId());
                    if (!p.isUser()) {
                        throw UseCaseException.businessRule("NOT_A_USER", "IDP role sync only applies to USER principals");
                    }
                    for (String name : cmd.platformRoles()) {
                        if (roles.findByName(name).isEmpty()) {
                            throw UseCaseException.validation("ROLE_NOT_FOUND", "Role not found: " + name);
                        }
                    }
                    var change = p.syncSourcedRoles(RoleAssignment.IDP_SYNC, cmd.platformRoles());
                    return Plan.save(change.principal(), repo.withRoles(), RolesAssigned.of(ec, change));
                });
    }
}

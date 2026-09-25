package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.operations.RoleEvents.RoleCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a `DATABASE`-sourced role (unique by canonical name) with its
/// initial permission set and emits [RoleCreated].
public final class CreateRole {

    private CreateRole() {
    }

    public static Operation<CreateCommand, RoleCreated> of(RoleRepository repo) {
        return Operation.<CreateCommand, RoleCreated>named("CreateRole")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_REQUIRED", "applicationCode is required");
                    UseCaseException.requireNonBlank(cmd.roleName(), "ROLE_NAME_REQUIRED", "roleName is required");
                    UseCaseException.requireNonBlank(cmd.displayName(), "DISPLAY_NAME_REQUIRED", "displayName is required");
                })
                // Roles are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    String name = cmd.applicationCode() + ":" + cmd.roleName();
                    if (repo.findByName(name).isPresent()) {
                        throw UseCaseException.conflict("ROLE_EXISTS", "Role '" + name + "' already exists");
                    }
                    Role role = Role.create(cmd.applicationCode(), cmd.roleName(), cmd.displayName())
                            .withDescription(cmd.description())
                            .withClientManaged(cmd.clientManaged())
                            .withPermissions(cmd.permissions(), Access.crossApplication());
                    // Owner ruling 2026-09-25: only permissions the caller holds.
                    RoleCeiling.requirePermissions(Auth.current(), role.permissions());
                    return Plan.save(role, repo, RoleCreated.of(ec, role));
                });
    }
}

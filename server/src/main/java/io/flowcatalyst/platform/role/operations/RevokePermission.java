package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.operations.RoleEvents.RolePermissionRevoked;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Removes a permission from the role addressed by **name** and emits
/// [RolePermissionRevoked] — also for a permission not held (spec §2).
public final class RevokePermission {

    private RevokePermission() {
    }

    public static Operation<RevokePermissionCommand, RolePermissionRevoked> of(RoleRepository repo) {
        return Operation.<RevokePermissionCommand, RolePermissionRevoked>named("RevokePermission")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.roleName(), "ROLE_NAME_REQUIRED", "Role name is required");
                    UseCaseException.requireNonBlank(cmd.permission(), "PERMISSION_REQUIRED", "Permission is required");
                })
                // Roles are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Role before = Access.byName(repo, cmd.roleName());
                    Role role = before.revoke(cmd.permission());
                    // Owner ruling 2026-09-25: removal counts, so only a permission the caller holds.
                    RoleCeiling.requirePermissions(Auth.current(), RoleCeiling.changed(before.permissions(), role.permissions()));
                    return Plan.save(role, repo, RolePermissionRevoked.of(ec, role, cmd.permission()));
                });
    }
}

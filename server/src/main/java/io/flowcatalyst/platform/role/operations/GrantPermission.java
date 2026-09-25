package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.role.operations.RoleEvents.RolePermissionGranted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Adds a permission to the role addressed by **name** and emits
/// [RolePermissionGranted] — also for a permission already held, so the
/// audit trail records the admin action (spec §2). Allowed on every
/// source, including `CODE` (open question 1).
public final class GrantPermission {

    private GrantPermission() {
    }

    public static Operation<GrantPermissionCommand, RolePermissionGranted> of(RoleRepository repo) {
        return Operation.<GrantPermissionCommand, RolePermissionGranted>named("GrantPermission")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.roleName(), "ROLE_NAME_REQUIRED", "Role name is required");
                    UseCaseException.requireNonBlank(cmd.permission(), "PERMISSION_REQUIRED", "Permission is required");
                })
                // Roles are global — no per-resource dimension; the handler's coarse gate is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Role before = Access.byName(repo, cmd.roleName());
                    Role role = before.grant(cmd.permission(), Access.crossApplication());
                    // Owner ruling 2026-09-25: only a permission the caller holds.
                    RoleCeiling.requirePermissions(Auth.current(), RoleCeiling.changed(before.permissions(), role.permissions()));
                    return Plan.save(role, repo, RolePermissionGranted.of(ec, role, cmd.permission()));
                });
    }
}

package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleCeiling;
import io.flowcatalyst.platform.role.RoleName;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountRolesAssigned;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.List;

/// Replaces the role assignments of the service account's linked `SERVICE`
/// principal wholesale and emits [ServiceAccountRolesAssigned] with the
/// set-difference (spec §4.5). Roles live in `iam_principal_roles`, keyed by
/// the principal — NOT the service-account row — so this writes through
/// [PrincipalRepository#withRoles()], not [ServiceAccountRepository]. This is
/// why the route is anchor-only (spec §3): it grants authority in the
/// `principal` aggregate. Reuses [Principal#assignRoles] verbatim — same
/// "every assignment becomes `ADMIN_ASSIGNED`" adoption rule the principal
/// aggregate's own admin-assign route uses.
public final class AssignRolesToServiceAccount {

    private AssignRolesToServiceAccount() {
    }

    /// @param permissionsOf a role's permissions, for the ceiling (owner ruling 2026-09-25:
    ///                      only roles whose permissions the caller holds may be added or removed)
    public static Operation<AssignRolesCommand, ServiceAccountRolesAssigned> of(ServiceAccountRepository saRepo, PrincipalRepository principals,
                                                                               RoleRepository roles, RoleCeiling.RolePermissions permissionsOf) {
        return Operation.<AssignRolesCommand, ServiceAccountRolesAssigned>named("AssignRolesToServiceAccount")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.serviceAccountId(), "SERVICE_ACCOUNT_ID_REQUIRED", "Service account ID is required"))
                .authorize(cmd -> Access.requireWrite(saRepo, Auth.current(), cmd.serviceAccountId())) // application confinement (F4); the permission and anchor reach are the handler's
                .execute((cmd, ec) -> {
                    ServiceAccount sa = Access.byId(saRepo, cmd.serviceAccountId());
                    Principal principal = principals.findByServiceAccount(sa.id())
                            .orElseThrow(() -> UseCaseException.internal("PRINCIPAL",
                                    "service account " + sa.id() + " has no linked principal", null));

                    // The root of Go F18: this path stored whatever string it was sent
                    // ("example: dispatcher") and minted it into the token. Every name is
                    // shape-checked (400 INVALID_ROLE_NAME) and must name an existing role
                    // (400 ROLE_NOT_FOUND); the assignments are built from the resolved RoleName.
                    List<RoleName> resolved = RoleName.resolveAll(roles, cmd.roles());

                    RoleCeiling.requireRoles(Auth.current(), RoleCeiling.changed(principal.roleNames(), cmd.roles()), permissionsOf);
                    Principal.RolesChanged changed = principal.assignRoles(resolved);
                    var event = ServiceAccountRolesAssigned.of(ec, sa.id(), changed.added(), changed.removed());
                    return Plan.save(changed.principal(), principals.withRoles(), event);
                });
    }
}

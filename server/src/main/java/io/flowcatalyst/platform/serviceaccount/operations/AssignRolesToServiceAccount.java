package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountRolesAssigned;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

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

    public static Operation<AssignRolesCommand, ServiceAccountRolesAssigned> of(ServiceAccountRepository saRepo, PrincipalRepository principals) {
        return Operation.<AssignRolesCommand, ServiceAccountRolesAssigned>named("AssignRolesToServiceAccount")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.serviceAccountId(), "SERVICE_ACCOUNT_ID_REQUIRED", "Service account ID is required"))
                .authorize(Operation.Authorize.publicAccess()) // anchor-only gate is enforced at the handler (spec §3)
                .execute((cmd, ec) -> {
                    ServiceAccount sa = Access.byId(saRepo, cmd.serviceAccountId());
                    Principal principal = principals.findByServiceAccount(sa.id())
                            .orElseThrow(() -> UseCaseException.internal("PRINCIPAL",
                                    "service account " + sa.id() + " has no linked principal", null));

                    Principal.RolesChanged changed = principal.assignRoles(cmd.roles());
                    var event = ServiceAccountRolesAssigned.of(ec, sa.id(), changed.added(), changed.removed());
                    return Plan.save(changed.principal(), principals.withRoles(), event);
                });
    }
}

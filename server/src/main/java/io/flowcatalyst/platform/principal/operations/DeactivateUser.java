package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserDeactivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// `active = false` and emits [UserDeactivated] (idempotent, spec §2).
public final class DeactivateUser {

    private DeactivateUser() {
    }

    public static Operation<DeactivateCommand, UserDeactivated> of(PrincipalRepository repo) {
        return Operation.<DeactivateCommand, UserDeactivated>named("DeactivateUser")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource rule runs post-load: Access.requireManageable
                .execute((cmd, ec) -> {
                    Principal p = Access.loadPrincipal(repo, cmd.id());
                    Access.requireManageable(p);
                    p = p.deactivate();
                    return Plan.save(p, repo, UserDeactivated.of(ec, p));
                });
    }
}

package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserActivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// `active = true` and emits [UserActivated] (idempotent, spec §2).
public final class ActivateUser {

    private ActivateUser() {
    }

    public static Operation<ActivateCommand, UserActivated> of(PrincipalRepository repo) {
        return Operation.<ActivateCommand, UserActivated>named("ActivateUser")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource rule runs post-load: Access.requireManageable
                .execute((cmd, ec) -> {
                    Principal p = Access.loadPrincipal(repo, cmd.id());
                    Access.requireManageable(p);
                    p = p.activate();
                    return Plan.save(p, repo, UserActivated.of(ec, p));
                });
    }
}

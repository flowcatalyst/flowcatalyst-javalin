package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Removes a principal (row + junctions) and emits [UserDeleted].
public final class DeleteUser {

    private DeleteUser() {
    }

    public static Operation<DeleteCommand, UserDeleted> of(PrincipalRepository repo) {
        return Operation.<DeleteCommand, UserDeleted>named("DeleteUser")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource rule runs post-load: Access.requireManageable
                .execute((cmd, ec) -> {
                    Principal p = Access.loadPrincipal(repo, cmd.id());
                    Access.requireManageable(p);
                    return Plan.delete(p, repo, UserDeleted.of(ec, p));
                });
    }
}

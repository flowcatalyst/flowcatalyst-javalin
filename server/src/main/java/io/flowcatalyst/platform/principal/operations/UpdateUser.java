package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// The admin update (spec §4): name and active status, with `email` as an
/// identity assertion; emits [UserUpdated]. Scope and client association are
/// deliberately not here — they are anchor-gated changes on their own route.
public final class UpdateUser {

    private UpdateUser() {
    }

    public static Operation<UpdateCommand, UserUpdated> of(PrincipalRepository repo) {
        return Operation.<UpdateCommand, UserUpdated>named("UpdateUser")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource rule runs post-load: Access.requireManageable
                .execute((cmd, ec) -> {
                    Principal p = Access.loadPrincipal(repo, cmd.id());
                    Access.requireManageable(p);
                    p = p.update(new Principal.Changes(cmd.name(), cmd.active(), cmd.email()));
                    return Plan.save(p, repo, UserUpdated.of(ec, p));
                });
    }
}

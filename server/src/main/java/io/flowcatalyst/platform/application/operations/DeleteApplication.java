package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an application and emits [ApplicationDeleted]. Client
/// configs are not cascaded (spec §3, open question 6).
public final class DeleteApplication {

    private DeleteApplication() {
    }

    public static Operation<DeleteCommand, ApplicationDeleted> of(ApplicationRepository repo) {
        return Operation.<DeleteCommand, ApplicationDeleted>named("DeleteApplication")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // platform-level; load-or-404 is in Access.byId
                .execute((cmd, ec) -> {
                    Application a = Access.byId(repo, cmd.id());
                    return Plan.delete(a, repo, ApplicationDeleted.of(ec, a));
                });
    }
}

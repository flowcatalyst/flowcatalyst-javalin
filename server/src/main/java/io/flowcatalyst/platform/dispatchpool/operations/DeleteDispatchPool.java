package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a dispatch pool and emits [DispatchPoolDeleted].
public final class DeleteDispatchPool {

    private DeleteDispatchPool() {
    }

    public static Operation<DeleteCommand, DispatchPoolDeleted> of(DispatchPoolRepository repo) {
        return Operation.<DeleteCommand, DispatchPoolDeleted>named("DeleteDispatchPool")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    DispatchPool p = Access.loadScoped(repo, cmd.id());
                    return Plan.delete(p, repo, DispatchPoolDeleted.of(ec, p));
                });
    }
}

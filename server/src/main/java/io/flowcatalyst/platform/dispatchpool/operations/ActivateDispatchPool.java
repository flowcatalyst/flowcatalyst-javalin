package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolActivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `ACTIVE` ([DispatchPool#activate]) and emits [DispatchPoolActivated].
public final class ActivateDispatchPool {

    private ActivateDispatchPool() {
    }

    public static Operation<ActivateCommand, DispatchPoolActivated> of(DispatchPoolRepository repo) {
        return Operation.<ActivateCommand, DispatchPoolActivated>named("ActivateDispatchPool")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    DispatchPool p = Access.loadScoped(repo, cmd.id()).activate();
                    return Plan.save(p, repo, DispatchPoolActivated.of(ec, p));
                });
    }
}

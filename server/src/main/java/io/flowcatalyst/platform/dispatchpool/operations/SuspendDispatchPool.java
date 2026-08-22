package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolSuspended;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `SUSPENDED` ([DispatchPool#suspend]) and emits [DispatchPoolSuspended].
/// Only the routing-eligibility flag flips; in-flight work is untouched.
public final class SuspendDispatchPool {

    private SuspendDispatchPool() {
    }

    public static Operation<SuspendCommand, DispatchPoolSuspended> of(DispatchPoolRepository repo) {
        return Operation.<SuspendCommand, DispatchPoolSuspended>named("SuspendDispatchPool")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    DispatchPool p = Access.loadScoped(repo, cmd.id()).suspend();
                    return Plan.save(p, repo, DispatchPoolSuspended.of(ec, p));
                });
    }
}

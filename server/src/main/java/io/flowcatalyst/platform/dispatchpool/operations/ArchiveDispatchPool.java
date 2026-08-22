package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolArchived;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `ARCHIVED` ([DispatchPool#archive]) and emits [DispatchPoolArchived].
public final class ArchiveDispatchPool {

    private ArchiveDispatchPool() {
    }

    public static Operation<ArchiveCommand, DispatchPoolArchived> of(DispatchPoolRepository repo) {
        return Operation.<ArchiveCommand, DispatchPoolArchived>named("ArchiveDispatchPool")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    DispatchPool p = Access.loadScoped(repo, cmd.id()).archive();
                    return Plan.save(p, repo, DispatchPoolArchived.of(ec, p));
                });
    }
}

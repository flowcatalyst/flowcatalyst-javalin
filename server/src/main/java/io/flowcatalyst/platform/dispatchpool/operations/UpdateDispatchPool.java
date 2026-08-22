package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents.DispatchPoolUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Applies the given settings to an existing pool (absent ones are kept) and
/// emits [DispatchPoolUpdated]. The code is immutable.
public final class UpdateDispatchPool {

    private UpdateDispatchPool() {
    }

    public static Operation<UpdateCommand, DispatchPoolUpdated> of(DispatchPoolRepository repo) {
        return Operation.<UpdateCommand, DispatchPoolUpdated>named("UpdateDispatchPool")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                    Bounds.checkAdmin(cmd.concurrency(), cmd.rateLimit());
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    DispatchPool p = Access.loadScoped(repo, cmd.id());
                    if (cmd.name() != null) p = p.withName(cmd.name().trim());
                    if (cmd.description() != null) p = p.withDescription(cmd.description());
                    if (cmd.rateLimit() != null) p = p.withRateLimit(cmd.rateLimit());
                    if (cmd.concurrency() != null) p = p.withConcurrency(cmd.concurrency());
                    return Plan.save(p, repo, DispatchPoolUpdated.of(ec, p));
                });
    }
}

package io.flowcatalyst.platform.dispatchjob.operations;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents.DispatchJobCancelled;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// The operator's "ignore" (dispatch-seam spec §8, router-spec §3.2): flips a
/// `FAILED` job to `CANCELLED` ([DispatchJob#cancel]). Only valid from
/// `FAILED` — 409 `NOT_FAILED` otherwise ([StatusFlip#requireFailed]).
/// Flipping the head off `FAILED` is also what unblocks the rest of its
/// `BLOCK_ON_ERROR` message group: [DispatchJobRepository#groupHeldBefore]'s
/// predicate stops matching the instant the status changes.
public final class CancelDispatchJob {

    private CancelDispatchJob() {
    }

    public static Operation<CancelCommand, DispatchJobCancelled> of(DispatchJobRepository repo) {
        return Operation.<CancelCommand, DispatchJobCancelled>named("CancelDispatchJob")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadOwn
                .execute((cmd, ec) -> {
                    DispatchJob j = StatusFlip.requireFailed(Access.loadOwn(repo, cmd.id())).cancel();
                    return Plan.save(j, repo, DispatchJobCancelled.of(ec, j));
                });
    }
}

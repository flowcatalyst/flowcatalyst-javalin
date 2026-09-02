package io.flowcatalyst.platform.dispatchjob.operations;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobRepository;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents.DispatchJobCompleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// The operator's "mark handled out of band" (dispatch-seam spec §8,
/// router-spec §3.2's "completed"): flips a `FAILED` job to `COMPLETED`
/// ([DispatchJob#complete]). Same gating and precondition shape as
/// [CancelDispatchJob] — only valid from `FAILED` (409 `NOT_FAILED`
/// otherwise); also unblocks the rest of the job's `BLOCK_ON_ERROR` group.
public final class CompleteDispatchJob {

    private CompleteDispatchJob() {
    }

    public static Operation<CompleteCommand, DispatchJobCompleted> of(DispatchJobRepository repo) {
        return Operation.<CompleteCommand, DispatchJobCompleted>named("CompleteDispatchJob")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in StatusFlip.loadOwn
                .execute((cmd, ec) -> {
                    DispatchJob j = StatusFlip.requireFailed(StatusFlip.loadOwn(repo, cmd.id())).complete();
                    return Plan.save(j, repo, DispatchJobCompleted.of(ec, j));
                });
    }
}

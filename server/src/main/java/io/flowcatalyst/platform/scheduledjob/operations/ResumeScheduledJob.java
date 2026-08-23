package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobResumed;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `ACTIVE` ([ScheduledJob#resume]) and emits [ScheduledJobResumed].
public final class ResumeScheduledJob {

    private ResumeScheduledJob() {
    }

    public static Operation<ResumeCommand, ScheduledJobResumed> of(ScheduledJobRepository repo) {
        return Operation.<ResumeCommand, ScheduledJobResumed>named("ResumeScheduledJob")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    ScheduledJob j = Access.loadScoped(repo, cmd.id()).resume(ec.principalId());
                    return Plan.save(j, repo, ScheduledJobResumed.of(ec, j));
                });
    }
}

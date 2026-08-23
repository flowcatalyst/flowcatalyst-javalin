package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobPaused;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `PAUSED` ([ScheduledJob#pause]) and emits [ScheduledJobPaused].
public final class PauseScheduledJob {

    private PauseScheduledJob() {
    }

    public static Operation<PauseCommand, ScheduledJobPaused> of(ScheduledJobRepository repo) {
        return Operation.<PauseCommand, ScheduledJobPaused>named("PauseScheduledJob")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    ScheduledJob j = Access.loadScoped(repo, cmd.id()).pause(ec.principalId());
                    return Plan.save(j, repo, ScheduledJobPaused.of(ec, j));
                });
    }
}

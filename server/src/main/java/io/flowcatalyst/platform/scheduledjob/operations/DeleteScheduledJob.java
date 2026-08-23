package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes the job row (instances and logs stay, spec §4) and emits
/// [ScheduledJobDeleted].
public final class DeleteScheduledJob {

    private DeleteScheduledJob() {
    }

    public static Operation<DeleteCommand, ScheduledJobDeleted> of(ScheduledJobRepository repo) {
        return Operation.<DeleteCommand, ScheduledJobDeleted>named("DeleteScheduledJob")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    ScheduledJob j = Access.loadScoped(repo, cmd.id());
                    return Plan.delete(j, repo, ScheduledJobDeleted.of(ec, j));
                });
    }
}

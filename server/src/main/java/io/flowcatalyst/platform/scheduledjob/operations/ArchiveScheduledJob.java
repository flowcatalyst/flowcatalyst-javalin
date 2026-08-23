package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobArchived;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `ARCHIVED` ([ScheduledJob#archive]) and emits [ScheduledJobArchived].
public final class ArchiveScheduledJob {

    private ArchiveScheduledJob() {
    }

    public static Operation<ArchiveCommand, ScheduledJobArchived> of(ScheduledJobRepository repo) {
        return Operation.<ArchiveCommand, ScheduledJobArchived>named("ArchiveScheduledJob")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    ScheduledJob j = Access.loadScoped(repo, cmd.id()).archive(ec.principalId());
                    return Plan.save(j, repo, ScheduledJobArchived.of(ec, j));
                });
    }
}

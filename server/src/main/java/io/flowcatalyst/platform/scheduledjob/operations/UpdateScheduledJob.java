package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// A partial update of a job's mutable fields ([ScheduledJob#update]) and
/// emits [ScheduledJobUpdated]. The code and scope are immutable.
public final class UpdateScheduledJob {

    private UpdateScheduledJob() {
    }

    public static Operation<UpdateCommand, ScheduledJobUpdated> of(ScheduledJobRepository repo) {
        return Operation.<UpdateCommand, ScheduledJobUpdated>named("UpdateScheduledJob")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                    if (cmd.crons() != null) {
                        Crons.parseAll(cmd.crons());
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    ScheduledJob j = Access.loadScoped(repo, cmd.id()).update(changesOf(cmd), ec.principalId());
                    return Plan.save(j, repo, ScheduledJobUpdated.of(ec, j));
                });
    }

    private static ScheduledJob.Changes changesOf(UpdateCommand cmd) {
        return new ScheduledJob.Changes(cmd.name(), cmd.description(),
                cmd.crons() == null ? null : Crons.parseAll(cmd.crons()),
                cmd.timezone(), cmd.payload(), cmd.concurrent(), cmd.tracksCompletion(), cmd.timeoutSeconds(),
                cmd.deliveryMaxAttempts(), cmd.targetUrl());
    }
}

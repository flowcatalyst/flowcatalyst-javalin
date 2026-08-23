package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents.ScheduledJobFiredManually;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// A manual fire (spec §6.1): mints the `MANUAL`, `QUEUED` instance
/// ([ScheduledJob#fireNow]), inserts it **directly** — instances are a
/// projection, not an aggregate — and then emits [ScheduledJobFiredManually]
/// through the envelope. Two-phase on purpose: a failed insert yields no
/// event. A `PAUSED` job is firable (the human override); only `ARCHIVED`
/// is refused.
public final class FireNow {

    private FireNow() {
    }

    public static Operation<FireNowCommand, ScheduledJobFiredManually> of(ScheduledJobRepository repo,
                                                                          ScheduledJobInstanceRepository instances) {
        return Operation.<FireNowCommand, ScheduledJobFiredManually>named("FireNow")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    ScheduledJob j = Access.loadScoped(repo, cmd.id());
                    ScheduledJobInstance inst = j.fireNow(cmd.correlationId());
                    instances.insert(inst);
                    return Plan.emit(ScheduledJobFiredManually.of(ec, j, inst.id()));
                });
    }
}

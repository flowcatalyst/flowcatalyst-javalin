package io.flowcatalyst.function;

import java.time.Instant;
import java.util.Objects;

/// A scheduled (cron) firing: `address`/`invocationId` per [Invocation],
/// plus the schedule expression and the instant it fired for.
///
/// @param schedule    the cron expression this firing came from
/// @param scheduledFor the instant this firing was due
public record ScheduleInvocation(
        FunctionAddress address, String invocationId, String schedule, Instant scheduledFor)
        implements Invocation {

    public ScheduleInvocation {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(scheduledFor, "scheduledFor");
    }
}

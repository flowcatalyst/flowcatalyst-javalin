package io.flowcatalyst.platform.scheduler.jobs.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.StackTrace;

/// Shared shape for the scheduled-job dispatcher's flight-recorder events,
/// mirroring [io.flowcatalyst.router.observability.jfr.RouterEvent]'s
/// pattern: always compiled in, costs nothing while disabled, and can be
/// turned on against a running process. [StackTrace] is off — the stack at
/// a firing's terminal mark is [io.flowcatalyst.platform.scheduler.jobs.JobDispatcher]'s
/// own dispatch loop every time.
@Category({"FlowCatalyst", "ScheduledJob"})
@StackTrace(false)
abstract class JobDispatcherEvent extends Event {
}

package io.flowcatalyst.platform.scheduler.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.StackTrace;

/// Shared shape for the scheduler's flight-recorder events, following
/// [io.flowcatalyst.router.observability.jfr.RouterEvent]'s pattern: always
/// compiled in, costs nothing while disabled, and can be turned on against a
/// running process. [StackTrace] is off — the stack at a poll tick is the
/// poller's own loop every time, the most expensive field and the least
/// informative one.
@Category({"FlowCatalyst", "Scheduler"})
@StackTrace(false)
abstract class SchedulerEvent extends Event {
}

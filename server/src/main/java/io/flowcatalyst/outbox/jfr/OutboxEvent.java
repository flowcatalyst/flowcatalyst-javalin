package io.flowcatalyst.outbox.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.StackTrace;

/// Shared shape for the outbox loop's flight-recorder events, mirroring
/// [io.flowcatalyst.router.observability.jfr.RouterEvent]'s pattern: always
/// compiled in, costs nothing while disabled, and can be turned on against a
/// running process. [StackTrace] is off — the stack at an outcome apply is
/// the processor's own dispatch-worker loop every time.
@Category({"FlowCatalyst", "Outbox"})
@StackTrace(false)
abstract class OutboxEvent extends Event {
}

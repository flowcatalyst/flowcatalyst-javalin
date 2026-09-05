package io.flowcatalyst.stream.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.StackTrace;

/// Shared shape for the stream projectors' flight-recorder events, mirroring
/// [io.flowcatalyst.router.observability.jfr.RouterEvent]'s pattern: always
/// compiled in, costs nothing while disabled, and can be turned on against a
/// running process — which is when "did this batch actually insert
/// anything" gets asked.
///
/// [StackTrace] is off: the stack at a fan-out commit is the projector's own
/// step loop every time, the most expensive field and the least
/// informative.
@Category({"FlowCatalyst", "Stream"})
@StackTrace(false)
abstract class StreamEvent extends Event {
}

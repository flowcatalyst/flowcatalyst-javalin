package io.flowcatalyst.platform.purger.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.StackTrace;

/// Shared shape for the purger's flight-recorder events, mirroring
/// [io.flowcatalyst.router.observability.jfr.RouterEvent]'s pattern: always
/// compiled in, costs nothing while disabled, and can be turned on against a
/// running process. [StackTrace] is off — the stack at a purger step is
/// [io.flowcatalyst.platform.purger.Purger]'s own tick loop every time.
@Category({"FlowCatalyst", "Purger"})
@StackTrace(false)
abstract class PurgerEvent extends Event {
}

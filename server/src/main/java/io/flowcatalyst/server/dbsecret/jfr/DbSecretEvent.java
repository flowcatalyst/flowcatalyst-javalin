package io.flowcatalyst.server.dbsecret.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.StackTrace;

/// Shared shape for the DB-secret refresher's flight-recorder events,
/// mirroring [io.flowcatalyst.router.observability.jfr.RouterEvent]'s
/// pattern: always compiled in, costs nothing while disabled, and can be
/// turned on against a running process. [StackTrace] is off — the stack at
/// a refresh is [io.flowcatalyst.server.dbsecret.DbSecretRefresher]'s own
/// scheduled tick every time.
@Category({"FlowCatalyst", "Database"})
@StackTrace(false)
abstract class DbSecretEvent extends Event {
}

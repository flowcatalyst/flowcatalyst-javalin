package io.flowcatalyst.platform.scheduler.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One housekeeping sweep of the scheduler's leader over `msg_dispatch_queue`
/// (stale claims released, stale `QUEUED` jobs recovered, reconcile repairs):
/// which sweep, and how many rows it changed. The event's own duration is the
/// statement(s). Emitted for every sweep that runs, including those that change nothing.
@Name("io.flowcatalyst.platform.scheduler.QueueSweep")
@Label("Dispatch Queue Sweep")
@Description("One leader housekeeping sweep over the dispatch queue: which sweep and how many rows it changed")
public final class QueueSweepEvent extends SchedulerEvent {

    @Label("Sweep")
    public String sweep;

    @Label("Rows Changed")
    public int changed;
}

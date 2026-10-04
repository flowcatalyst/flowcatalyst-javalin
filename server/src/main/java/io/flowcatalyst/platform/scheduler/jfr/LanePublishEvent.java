package io.flowcatalyst.platform.scheduler.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One batch a dispatcher lane published (dispatch-seam spec §3): how many
/// jobs it took from its channel, how many it dropped because their group was
/// poisoned by an earlier unpublished job, how many the broker accepted, how
/// many were left `PENDING`, and how many of the accepted ones the `QUEUED`
/// update found already moved on. The event's duration is the publish plus the
/// status update.
@Name("io.flowcatalyst.platform.scheduler.LanePublish")
@Label("Dispatch Lane Batch Published")
@Description("One dispatcher lane batch: jobs taken, dropped for a poisoned group, published, unpublished, QUEUED updates that matched no row")
public final class LanePublishEvent extends SchedulerEvent {

    @Label("Lane")
    public int lane;

    @Label("Taken")
    public int taken;

    @Label("Dropped (Poisoned Group)")
    public int dropped;

    @Label("Published")
    public int published;

    @Label("Unpublished")
    public int unpublished;

    @Label("Not Updated")
    public int notUpdated;
}

package io.flowcatalyst.platform.scheduler.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One poll tick's claim (dispatch-seam spec §3): how many `PENDING` rows
/// were claimed, how many of those actually got published, and how many
/// stayed `PENDING` behind a `BLOCK_ON_ERROR` hold-back. `size` always equals
/// `published + heldBack` plus whatever the paused-subscription filter
/// dropped.
@Name("io.flowcatalyst.platform.scheduler.ClaimedBatch")
@Label("Dispatch Batch Claimed")
@Description("One scheduler poll tick's claim: rows claimed, rows published, rows held back by a BLOCK_ON_ERROR sibling")
public final class ClaimedBatchEvent extends SchedulerEvent {

    @Label("Claimed")
    public int size;

    @Label("Published")
    public int published;

    @Label("Held Back")
    public int heldBack;
}

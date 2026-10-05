package io.flowcatalyst.platform.scheduler.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One claim by the scheduler's poller (dispatch-seam spec §3): how many rows
/// it asked for (the permits it held), how many came back, how many of those
/// were handed to a lane, and how many stayed queued behind a
/// `BLOCK_ON_ERROR` hold-back (their claims released). `size == submitted +
/// heldBack` plus the rows withheld behind a doomed in-flight job. The event's
/// own duration is the claim query plus the hold-back check. Paused
/// subscriptions are excluded by the claim query itself.
@Name("io.flowcatalyst.platform.scheduler.ClaimedBatch")
@Label("Dispatch Batch Claimed")
@Description("One scheduler claim: rows asked for, rows claimed, rows handed to a lane, rows held back by a BLOCK_ON_ERROR sibling")
public final class ClaimedBatchEvent extends SchedulerEvent {

    @Label("Wanted")
    public int wanted;

    @Label("Claimed")
    public int size;

    @Label("Submitted")
    public int submitted;

    @Label("Held Back")
    public int heldBack;

    @Label("In Flight")
    public int inFlight;
}

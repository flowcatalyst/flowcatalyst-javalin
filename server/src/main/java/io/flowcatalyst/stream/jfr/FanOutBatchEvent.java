package io.flowcatalyst.stream.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One `event_fan_out` claim-and-insert transaction that claimed at least
/// one `msg_events` row (`docs/spec/jfr-events.md` §1), committed once the
/// transaction itself has committed.
///
/// Without this, a row leaving `msg_events` unfanned tells an operator
/// nothing about whether it matched any subscription — [#jobsInserted] can
/// legitimately be zero (no pattern matched) or the whole batch can be the
/// claim-only path with [#noSubscriptions] true. Neither is visible from a
/// counter alone.
@Name("io.flowcatalyst.stream.FanOutBatch")
@Label("Fan-Out Batch")
@Description("One event_fan_out transaction that claimed at least one event")
public final class FanOutBatchEvent extends StreamEvent {

    /// Rows the `FOR UPDATE SKIP LOCKED` claim took.
    @Label("Events Claimed")
    public int eventsClaimed;

    /// Rows written to `msg_dispatch_jobs`.
    @Label("Dispatch Jobs Inserted")
    public int jobsInserted;

    /// Size of the subscription cache used for matching.
    @Label("Active Subscriptions")
    public int subscriptions;

    /// The claim-only path (`claimNoSubscriptions`) was taken.
    @Label("No Subscriptions")
    public boolean noSubscriptions;
}

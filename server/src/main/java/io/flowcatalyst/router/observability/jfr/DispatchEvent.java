package io.flowcatalyst.router.observability.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One delivery attempt against a mediation target.
///
/// A duration event, so a recording carries the latency distribution per pool
/// and per target without a histogram having been configured in advance —
/// and, unlike the metrics, keeps each attempt attached to the message and
/// status that produced it. That is what turns "p99 got worse" into "these
/// messages, against this target".
@Name("io.flowcatalyst.router.Dispatch")
@Label("Dispatch Attempt")
@Description("One attempt to deliver a message to its mediation target")
public final class DispatchEvent extends RouterEvent {

    @Label("Pool")
    public String pool;

    @Label("Message ID")
    public String messageId;

    @Label("Queue")
    public String queue;

    /// Empty for an unordered message.
    @Label("Group")
    public String group;

    /// 0 on the first attempt.
    @Label("Attempt")
    public int attempt;

    /// The outcome's kind — `Success`, `CircuitOpen`, `ErrorProcess`, and so on.
    @Label("Outcome")
    public String outcome;

    @Label("Disposition")
    public String disposition;

    /// 0 when no call was made — an open circuit, a connection failure.
    @Label("Status Code")
    public int statusCode;
}

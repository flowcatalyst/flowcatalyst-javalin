package io.flowcatalyst.router.observability.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One message leaving this process, and on whose authority.
///
/// The single most valuable event here, because it is the **choke point**:
/// every path that finishes with a message — delivered, dropped as poison,
/// handed back, or abandoned mid-shutdown — goes through `QueueBroker`. A
/// recording therefore answers the question that matters after an incident,
/// "what happened to message X", with no gaps and no inference.
///
/// [#action] is *what we did*; [#reason] is *who decided*. Both are needed:
/// an `ack` is correct after a 200 and catastrophic after an open circuit,
/// and the action alone cannot tell those apart.
@Name("io.flowcatalyst.router.MessageSettled")
@Label("Message Settled")
@Description("A message left this process: acknowledged, returned to the broker, or released")
public final class MessageSettledEvent extends RouterEvent {

    @Label("Message ID")
    public String messageId;

    @Label("Queue")
    public String queue;

    /// `ack` — deleted from the broker. `nack` — handed back for redelivery.
    /// `release` — ownership dropped with no broker call at all, which is
    /// correct only when the broker's own redelivery is what recovers it.
    @Label("Action")
    public String action;

    @Label("Reason")
    public String reason;

    /// The redelivery delay asked for, on a `nack`. Zero otherwise.
    @Label("Requested Delay")
    @jdk.jfr.Timespan(jdk.jfr.Timespan.SECONDS)
    public long requestedDelay;

    /// False when the broker did not confirm the acknowledgement, or when the
    /// queue was deregistered under the message and there was nothing to
    /// acknowledge it on. Either way it is likely to come back.
    @Label("Broker Confirmed")
    public boolean brokerConfirmed;
}

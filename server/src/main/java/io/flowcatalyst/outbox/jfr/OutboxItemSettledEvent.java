package io.flowcatalyst.outbox.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One outbox item's outcome applied (`docs/spec/jfr-events.md` §2),
/// committed in `OutboxProcessor.applyOutcome` after the repository mark
/// call returned or threw. [#persisted] is `false` exactly when the mark
/// threw — the event is still committed then, because "the repository
/// update failed and the item will be re-claimed" is precisely the thing an
/// operator needs visible.
@Name("io.flowcatalyst.outbox.OutboxItemSettled")
@Label("Outbox Item Settled")
@Description("One outbox item's dispatch outcome was applied: success or failure, requeued or terminal")
public final class OutboxItemSettledEvent extends OutboxEvent {

    @Label("Item ID")
    public String itemId;

    /// `OutboxItemType.name()`.
    @Label("Item Type")
    public String type;

    /// Nullable — ungrouped items carry none.
    @Label("Message Group")
    public String group;

    /// `SUCCESS` or `FAILURE`.
    @Label("Outcome")
    public String outcome;

    /// `OutboxStatus.name()` written on failure; `DELIVERED` on success.
    @Label("Status")
    public String status;

    /// A failure that goes back to `PENDING`.
    @Label("Requeued")
    public boolean requeued;

    /// Delivered on the per-group path.
    @Label("Ordered Group")
    public boolean grouped;

    /// The repository mark call (`markSuccess`/`markFailed`) succeeded.
    @Label("Repository Updated")
    public boolean persisted;
}

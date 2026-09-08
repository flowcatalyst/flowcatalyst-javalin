package io.flowcatalyst.platform.dispatchjob.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// One `POST /api/dispatch/process` call that reached a loaded, non-terminal
/// job (dispatch-seam spec §5) — a duration event spanning the delivery
/// attempt (or the hold-back check, when the job was held).
///
/// Mirrors the router's own `observability/jfr` events
/// (`io.flowcatalyst.router.observability.jfr.RouterEvent`): always
/// compiled in, free while disabled, and the one place that answers "job X
/// came through the callback, and what happened" after the fact — a counter
/// says how many, this says which one and why.
@Name("io.flowcatalyst.platform.dispatchjob.DispatchProcessed")
@Label("Dispatch Job Processed")
@Description("A dispatch job reached the processing endpoint: held, delivered, deferred or failed")
@Category({"FlowCatalyst", "Platform", "DispatchJob"})
@StackTrace(false)
public final class DispatchProcessedEvent extends Event {

    @Label("Job ID")
    public String jobId;

    /// `Held`, `Delivered`, `Deferred`, `Failed`, `AlreadyTerminal` or
    /// `AlreadyClaimed`.
    @Label("Result")
    public String result;

    /// 1-based; 0 when no delivery was attempted (the job was held).
    @Label("Attempt")
    public int attempt;

    /// Whether the delivery-time `GroupHeldBefore` gate held this job back
    /// (spec §5, §9).
    @Label("Held")
    public boolean held;
}

package io.flowcatalyst.platform.dispatchjob.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// A dispatch job status transition matched no row: the job was in a status
/// the transition may not leave (a late callback meeting a settled job), or it
/// had already moved on (a lost claim, a stale mark-QUEUED). Emitted by
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle]; the counter
/// `fc_dispatch_job_transition_refused_total` says how many, this says which.
@Name("io.flowcatalyst.platform.dispatchjob.DispatchTransitionRefused")
@Label("Dispatch Job Transition Refused")
@Description("A dispatch job status transition matched no row")
@Category({"FlowCatalyst", "Platform", "DispatchJob"})
@StackTrace(false)
public final class DispatchTransitionRefusedEvent extends Event {

    /// The `Transition` constant's name.
    @Label("Transition")
    public String transition;

    /// The job, when the transition addressed exactly one; `null` for a batch.
    @Label("Job ID")
    public String jobId;

    /// Rows that did not move.
    @Label("Refused")
    public int refused;
}

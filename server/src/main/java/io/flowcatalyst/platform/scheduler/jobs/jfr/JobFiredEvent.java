package io.flowcatalyst.platform.scheduler.jobs.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One scheduled-job instance's terminal outcome for this attempt
/// (`docs/spec/jfr-events.md` §3), committed in `JobDispatcher` after the
/// instance's terminal mark for this attempt (`markDelivered`,
/// `markDeliveryFailed`) has returned — never before, and never at all when
/// the mark call itself threw, since then nothing is known to have actually
/// happened to the row.
@Name("io.flowcatalyst.platform.scheduler.jobs.JobFired")
@Label("Scheduled Job Fired")
@Description("A scheduled job instance reached a terminal outcome for this attempt: delivered, failed, or orphaned")
public final class JobFiredEvent extends JobDispatcherEvent {

    @Label("Instance ID")
    public String instanceId;

    @Label("Job Code")
    public String jobCode;

    /// Attempts after `markInFlight`; 0 for an orphan (no `markInFlight` call
    /// is ever made for a job that no longer exists).
    @Label("Attempt")
    public int attempt;

    /// `DELIVERED`, `FAILED`, or `ORPHAN`.
    @Label("Outcome")
    public String outcome;

    /// No further attempts.
    @Label("Terminal")
    public boolean terminal;

    /// HTTP status; 0 when no response was ever obtained (no target URL, a
    /// network/connection failure, or an orphan).
    @Label("Status Code")
    public int statusCode;

    /// Whether the firing carried `X-FlowCatalyst-Signature`.
    @Label("Signed")
    public boolean signed;
}

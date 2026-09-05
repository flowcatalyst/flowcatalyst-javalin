package io.flowcatalyst.platform.purger.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One `Purger.tick` step (`docs/spec/jfr-events.md` §4), committed once per
/// `step(name, action)` after the action returned or threw. Every step is
/// independently logged on failure so one table's problem never stops the
/// rest of the pass (class doc, `Purger`) — this is that same independence,
/// made queryable after the fact rather than only visible in a log line at
/// the moment it happened.
@Name("io.flowcatalyst.platform.purger.PurgerStep")
@Label("Purger Step")
@Description("One purger tick step ran to completion or failed, independently of every other step")
public final class PurgerStepEvent extends PurgerEvent {

    /// The step name passed to `step(...)`.
    @Label("Step")
    public String step;

    @Label("Succeeded")
    public boolean succeeded;

    /// Exception class + message on failure, else `null`.
    @Label("Error")
    public String error;
}

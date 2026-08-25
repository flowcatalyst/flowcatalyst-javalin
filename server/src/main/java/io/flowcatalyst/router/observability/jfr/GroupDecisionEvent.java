package io.flowcatalyst.router.observability.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// What an ordered group did when its head failed.
///
/// A group decision can settle many messages at once — `BlockGroup` and
/// `ReturnGroup` both act on every buffered sibling — so it is the one place
/// where a single wrong answer loses an unbounded number of messages, and
/// where per-message events alone would show the symptom without the cause.
/// Recording the decision beside its inputs makes the two separable.
@Name("io.flowcatalyst.router.GroupDecision")
@Label("Ordered Group Decision")
@Description("An ordered group's head failed and the group's fate was decided")
public final class GroupDecisionEvent extends RouterEvent {

    @Label("Group")
    public String group;

    @Label("Dispatch Mode")
    public String dispatchMode;

    /// `RetryHead`, `ReturnGroup`, `Continue` or `BlockGroup`.
    @Label("Decision")
    public String decision;

    /// The outcome's disposition — the input the decision turns on.
    @Label("Disposition")
    public String disposition;

    @Label("Status Code")
    public int statusCode;

    @Label("Attempt")
    public int attempt;

    /// How many buffered siblings the decision took with it. The blast
    /// radius: on `Continue` and `RetryHead` it is zero by construction.
    @Label("Siblings Affected")
    public int siblingsAffected;
}

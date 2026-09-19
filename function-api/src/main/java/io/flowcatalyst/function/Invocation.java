package io.flowcatalyst.function;

/// One call into a [Function#handle], in one of three shapes. A sealed
/// interface rather than the design's `kind` enum with nullable halves: the
/// compiler tells a function author which cases exist, instead of a runtime
/// check on a tag.
public sealed interface Invocation permits EventInvocation, HttpInvocation, ScheduleInvocation {

    /// The function this invocation targets.
    FunctionAddress address();

    /// The host-assigned id for this one call, unique per attempt.
    String invocationId();
}

package io.flowcatalyst.function;

/// The interface every FlowCatalyst function implements. The host locates
/// one instance per loaded version (`docs/spec/function-host-core.md` §2.2)
/// and calls [#init], then [#handle] once per invocation, then [#stop] when
/// the version is unloaded.
public interface Function {

    /// Runs once, after construction and before the first [#handle] call.
    /// The default does nothing.
    default void init(FunctionContext ctx) throws Exception {
    }

    /// Handles one invocation and returns its outcome.
    Result handle(Invocation in, FunctionContext ctx) throws Exception;

    /// Runs once, when this version is being unloaded — after every
    /// in-flight [#handle] call has returned. The default does nothing.
    default void stop() throws Exception {
    }
}

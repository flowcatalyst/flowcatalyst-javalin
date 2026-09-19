package io.flowcatalyst.function;

/// Emits platform events on a function's behalf, over the function's own
/// service-account identity. The real implementation is slice D4; in D1
/// [FunctionContext#events()] returns one that throws
/// `UnsupportedOperationException`.
public interface Events {

    /// Emits `event`.
    void emit(OutboundEvent event) throws Exception;
}

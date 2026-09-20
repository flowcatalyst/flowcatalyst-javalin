package io.flowcatalyst.function;

/// Emits platform events on a function's behalf, through the host — no
/// application credential is ever handed to the function
/// (`docs/spec/function-context.md` §3, ruling R13). A function may emit
/// only event types its own application owns; anything else, and any
/// platform-side rejection, surfaces as [EventEmitException].
public interface Events {

    /// Emits `event`.
    ///
    /// @throws EventEmitException the platform refused the event (unowned
    ///                             type, bad dedup id, …) or could not be
    ///                             reached
    void emit(OutboundEvent event) throws Exception;
}

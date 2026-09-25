package io.flowcatalyst.function;

/// Emits platform events on a function's behalf, through the host — no
/// application credential is ever handed to the function
/// (`docs/spec/function-context.md` §3, ruling R13). A function may emit
/// only event types its own application owns.
public interface Events {

    /// Emits `event`, answering [EmitResult.Emitted] with the stored event's id,
    /// or [EmitResult.Refused] when the platform refused it (an unowned type, a
    /// bad or duplicate dedup id, …) or could not be reached. Never throws for
    /// either (owner ruling 2026-09-25, backlog item 11).
    EmitResult emit(OutboundEvent event);
}

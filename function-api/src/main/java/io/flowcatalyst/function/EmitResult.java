package io.flowcatalyst.function;

import java.util.Objects;

/// The outcome of [Events#emit] (owner ruling 2026-09-25, `docs/backlog.md`
/// §"Overnight review" item 11). A refusal is a routine outcome a function
/// branches on, so it is returned, not thrown (CONVENTIONS §8):
///
/// ```java
/// return switch (ctx.events().emit(event)) {
///     case EmitResult.Emitted e -> Result.ack();
///     case EmitResult.Refused r when r.retryable() -> Result.retry(Duration.ofSeconds(5));
///     case EmitResult.Refused r -> Result.fail("event emit refused: " + r.code());
/// };
/// ```
public sealed interface EmitResult permits EmitResult.Emitted, EmitResult.Refused {

    /// The platform accepted the event. `eventId` is the id the platform
    /// answered; a repeated `dedupId` is an idempotent success (the ingest
    /// routes' rule), and then it is not necessarily the id the first emit
    /// stored.
    record Emitted(String eventId) implements EmitResult {
        public Emitted {
            Objects.requireNonNull(eventId, "eventId");
        }
    }

    /// The event was not emitted.
    ///
    /// @param code    the platform's own error code (`EVENT_TYPE_NOT_OWNED`,
    ///                `DEDUP_ID_DUPLICATE`, `DEDUP_ID_REQUIRED`, `HOST_UNKNOWN`,
    ///                `FUNCTION_NOT_SERVED_BY_HOST`, …), or `UNAVAILABLE` when the
    ///                platform could not be reached
    /// @param status  the HTTP status the platform answered, or `503` for `UNAVAILABLE`
    /// @param message what went wrong, for a log
    record Refused(String code, int status, String message) implements EmitResult {
        public Refused {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }

        /// Whether trying again later may succeed: the platform was unreachable or
        /// failed (5xx), rather than refusing the event itself.
        public boolean retryable() {
            return status >= 500;
        }
    }
}

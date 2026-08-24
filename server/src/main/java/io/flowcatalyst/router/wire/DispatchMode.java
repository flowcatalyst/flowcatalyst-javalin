package io.flowcatalyst.router.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/// How the router must sequence a message relative to its message group.
///
/// The wire form is the enum name. Parsing is **lenient by contract**: any
/// unrecognised value — including an absent field — is [#IMMEDIATE]
/// (`docs/spec/router.md` §2.6). That leniency is load-bearing on both sides
/// of the port: the Go poller's per-mode filter relies on it, and a message
/// published before `dispatchMode` was carried at all must still route.
public enum DispatchMode {

    /// No ordering. Dispatched concurrently, one worker per message, bounded
    /// only by the pool's concurrency.
    IMMEDIATE,

    /// Per-group FIFO, but a failed head does **not** hold the group: the
    /// group continues with the next message, and the failed one waits for
    /// human review (ignore / completed / resend) before the platform
    /// re-queues it.
    ///
    /// This is a **deliberate deviation from the Go router**, which blocks
    /// the group for both ordered modes (`pool.go:272` branches only on
    /// ordering). Owner ruling, `docs/spec/router.md` §2.6 / §13 Q1.
    NEXT_ON_ERROR,

    /// Strict per-group FIFO. A failed head blocks the group; the router
    /// ACKs the queued siblings off the broker because the platform re-sends
    /// the whole group once the failure is resolved.
    BLOCK_ON_ERROR;

    /// The lenient parser. `null`, blank and unrecognised all yield
    /// [#IMMEDIATE] — never an exception, and never `null`.
    @JsonCreator
    public static DispatchMode parse(String raw) {
        if (raw == null) {
            return IMMEDIATE;
        }
        return switch (raw.trim()) {
            case "NEXT_ON_ERROR" -> NEXT_ON_ERROR;
            case "BLOCK_ON_ERROR" -> BLOCK_ON_ERROR;
            default -> IMMEDIATE;
        };
    }

    @JsonValue
    public String wireValue() {
        return name();
    }

    /// Whether the router must sequence this message within its group.
    ///
    /// This is the predicate the pool branches on, so it — not the field
    /// being populated — is what a conformance test must assert.
    public boolean requiresOrdering() {
        return this != IMMEDIATE;
    }
}

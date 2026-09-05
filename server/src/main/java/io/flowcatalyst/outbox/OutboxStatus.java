package io.flowcatalyst.outbox;

import java.util.Optional;

/// The `outbox_messages.status` SMALLINT codes (spec §2) plus the reader for
/// the wire item-status strings the ingest routes return in `results[]`
/// (spec §6).
///
/// `retryable`/`terminal` are supplied per constant at construction — never
/// defaulted — so [io.flowcatalyst.outbox.OutboxProcessor]'s outcome
/// handling has an explicit opinion for every status (CONVENTIONS §8: "a
/// defaulted member on a sealed interface is untested by construction";
/// enum constructor arguments carry the same guarantee, since every constant
/// must supply them).
public enum OutboxStatus {
    PENDING(0, false, false),
    SUCCESS(1, false, true),
    BAD_REQUEST(2, false, true),
    INTERNAL_ERROR(3, true, false),
    UNAUTHORIZED(4, true, false),
    FORBIDDEN(5, false, true),
    GATEWAY_ERROR(6, true, false),
    IN_PROGRESS(9, true, false);

    private final int code;
    private final boolean retryable;
    private final boolean terminal;

    OutboxStatus(int code, boolean retryable, boolean terminal) {
        this.code = code;
        this.retryable = retryable;
        this.terminal = terminal;
    }

    /// The stored `outbox_messages.status` SMALLINT value.
    public int code() {
        return code;
    }

    /// Whether a failure at this status should be requeued (subject to the
    /// `MaxRetries` budget) rather than written terminal (spec §2, §4).
    public boolean retryable() {
        return retryable;
    }

    /// Whether this status is a dead end regardless of remaining retry
    /// budget: `SUCCESS` (row deleted), `BAD_REQUEST` and `FORBIDDEN` (spec §2).
    public boolean terminal() {
        return terminal;
    }

    /// The lenient reader for one `results[].status` wire value (spec §6):
    /// `SUCCESS`, `SKIPPED` (audit-only, reads as [#SUCCESS] — a skipped
    /// audit row is deleted exactly like a delivered one), `BAD_REQUEST`,
    /// `INTERNAL_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `GATEWAY_ERROR`.
    /// `PENDING`/`IN_PROGRESS` are stored-only and never appear on the wire,
    /// so they are not accepted here. An unrecognised value returns empty
    /// rather than guessing — [HttpDispatcher] is the caller that turns an
    /// empty result into the explicit `INTERNAL_ERROR "unknown item status:
    /// …"` outcome; this parser never silently defaults.
    public static Optional<OutboxStatus> parse(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        return switch (wire) {
            case "SUCCESS", "SKIPPED" -> Optional.of(SUCCESS);
            case "BAD_REQUEST" -> Optional.of(BAD_REQUEST);
            case "INTERNAL_ERROR" -> Optional.of(INTERNAL_ERROR);
            case "UNAUTHORIZED" -> Optional.of(UNAUTHORIZED);
            case "FORBIDDEN" -> Optional.of(FORBIDDEN);
            case "GATEWAY_ERROR" -> Optional.of(GATEWAY_ERROR);
            default -> Optional.empty();
        };
    }
}

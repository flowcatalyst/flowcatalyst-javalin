package io.flowcatalyst.platform.subscription;

/// How the router orders deliveries within a message group for one
/// subscription: `IMMEDIATE` (no ordering), `NEXT_ON_ERROR` (FIFO, a failure
/// releases the next), `BLOCK_ON_ERROR` (FIFO, a failure blocks the group).
/// The constant name is the stored and wire string. This is the router's
/// enum too; it lives here only until the data plane lands, when it moves to
/// a shared `messaging` package and this aggregate imports it (spec §9a).
public enum DispatchMode {
    IMMEDIATE, NEXT_ON_ERROR, BLOCK_ON_ERROR;

    /// Lenient reader for stored and wire values: unknown (and `null`) →
    /// `IMMEDIATE` (spec §1, open question 7).
    public static DispatchMode parse(String s) {
        return switch (s == null ? "" : s) {
            case "NEXT_ON_ERROR" -> NEXT_ON_ERROR;
            case "BLOCK_ON_ERROR" -> BLOCK_ON_ERROR;
            default -> IMMEDIATE;
        };
    }

    /// Whether the mode demands FIFO processing within a message group.
    public boolean requiresOrdering() {
        return switch (this) {
            case NEXT_ON_ERROR, BLOCK_ON_ERROR -> true;
            case IMMEDIATE -> false;
        };
    }
}

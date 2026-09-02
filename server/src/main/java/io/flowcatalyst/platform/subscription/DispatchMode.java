package io.flowcatalyst.platform.subscription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// How the router orders deliveries within a message group for one
/// subscription: `IMMEDIATE` (no ordering), `NEXT_ON_ERROR` (FIFO, a failure
/// releases the next), `BLOCK_ON_ERROR` (FIFO, a failure blocks the group).
/// The constant name is the stored and wire string. This is the router's
/// enum too; it lives here only until the data plane lands, when it moves to
/// a shared `messaging` package and this aggregate imports it (spec §9a).
public enum DispatchMode {
    IMMEDIATE, NEXT_ON_ERROR, BLOCK_ON_ERROR;

    private static final Logger LOG = LoggerFactory.getLogger(DispatchMode.class);

    /// Lenient reader for stored and wire values: absent (`null`/blank) →
    /// `NEXT_ON_ERROR` silently — the ordering-safe default (dispatch-seam
    /// spec §2 "`dispatchMode` resolution", X-01/A-09: `IMMEDIATE` is "the
    /// only mode with no ordering at all", so a default that quietly
    /// weakens the ordering guarantee is the wrong way round). A genuinely
    /// unrecognised non-blank value (a producer bug) also falls back to
    /// `NEXT_ON_ERROR`, but is logged at WARN so the bad producer is visible.
    public static DispatchMode parse(String s) {
        if (s == null || s.isEmpty()) return NEXT_ON_ERROR;
        return switch (s) {
            case "IMMEDIATE" -> IMMEDIATE;
            case "NEXT_ON_ERROR" -> NEXT_ON_ERROR;
            case "BLOCK_ON_ERROR" -> BLOCK_ON_ERROR;
            default -> {
                LOG.warn("unrecognised dispatchMode '{}'; defaulting to NEXT_ON_ERROR", s);
                yield NEXT_ON_ERROR;
            }
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

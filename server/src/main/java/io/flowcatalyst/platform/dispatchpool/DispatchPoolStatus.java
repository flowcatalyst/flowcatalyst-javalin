package io.flowcatalyst.platform.dispatchpool;

/// The pool's routing-eligibility state: `ACTIVE` (routable), `SUSPENDED`
/// (dispatch paused, in-flight work untouched) or `ARCHIVED` (retired). The
/// transitions between them are unconditional flips (spec §2). The constant
/// name is the stored and wire string.
public enum DispatchPoolStatus {
    ACTIVE, SUSPENDED, ARCHIVED;

    /// Lenient reader for stored values: unknown → `ACTIVE` (spec §1).
    public static DispatchPoolStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "SUSPENDED" -> SUSPENDED;
            case "ARCHIVED" -> ARCHIVED;
            default -> ACTIVE;
        };
    }
}

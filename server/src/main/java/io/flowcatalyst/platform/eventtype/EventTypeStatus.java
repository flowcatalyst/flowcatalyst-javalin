package io.flowcatalyst.platform.eventtype;

/// The event-type lifecycle state: `CURRENT` → `ARCHIVED` (one way). The
/// constant name is the stored and wire string.
public enum EventTypeStatus {
    CURRENT, ARCHIVED;

    /// Lenient reader for stored values: unknown → `CURRENT` (spec §1).
    public static EventTypeStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "ARCHIVED" -> ARCHIVED;
            default -> CURRENT;
        };
    }
}

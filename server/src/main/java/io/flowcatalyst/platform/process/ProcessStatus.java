package io.flowcatalyst.platform.process;

/// The process lifecycle state: `CURRENT` → `ARCHIVED` (one way). The
/// constant name is the stored and wire string.
public enum ProcessStatus {
    CURRENT, ARCHIVED;

    /// Lenient reader for stored values: unknown → `CURRENT` (spec §1).
    public static ProcessStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "ARCHIVED" -> ARCHIVED;
            default -> CURRENT;
        };
    }
}

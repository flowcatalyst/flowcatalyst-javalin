package io.flowcatalyst.platform.eventtype;

/// The schema-version state machine: `FINALISING` → `CURRENT` → `DEPRECATED`
/// (spec §2). The constant name is the stored and wire string.
public enum SpecVersionStatus {
    FINALISING, CURRENT, DEPRECATED;

    /// Lenient reader for stored values: unknown → `FINALISING` (spec §1).
    public static SpecVersionStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "CURRENT" -> CURRENT;
            case "DEPRECATED" -> DEPRECATED;
            default -> FINALISING;
        };
    }
}

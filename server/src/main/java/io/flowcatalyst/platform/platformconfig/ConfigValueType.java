package io.flowcatalyst.platform.platformconfig;

/// Whether a platform-config value is plain text or a secret. A `SECRET`
/// value is masked on read for non-anchor principals (spec §4). The constant
/// name is the stored and wire string.
public enum ConfigValueType {
    PLAIN, SECRET;

    /// Lenient reader for stored values and for the `valueType` a set command
    /// carries: unknown → `PLAIN` (spec §1.1, open question 6).
    public static ConfigValueType parse(String s) {
        return switch (s == null ? "" : s) {
            case "SECRET" -> SECRET;
            default -> PLAIN;
        };
    }
}

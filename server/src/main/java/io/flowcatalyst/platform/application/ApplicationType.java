package io.flowcatalyst.platform.application;

/// The application kind: a first-party `APPLICATION` or a third-party
/// `INTEGRATION`. The constant name is the stored and wire string.
public enum ApplicationType {
    APPLICATION, INTEGRATION;

    /// Lenient reader for stored and wire values: anything but `INTEGRATION`
    /// → `APPLICATION` (spec §1.1, §4 — an unknown `type` on create is not
    /// an error, open question 5).
    public static ApplicationType parse(String s) {
        return switch (s == null ? "" : s) {
            case "INTEGRATION" -> INTEGRATION;
            default -> APPLICATION;
        };
    }
}

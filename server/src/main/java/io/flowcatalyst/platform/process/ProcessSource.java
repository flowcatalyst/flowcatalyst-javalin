package io.flowcatalyst.platform.process;

/// Where the process was authored: `CODE` (the seeded catalogue), `API`
/// (application sync) or `UI` (admin create). The constant name is the
/// stored and wire string.
public enum ProcessSource {
    CODE, API, UI;

    /// Lenient reader for stored values: unknown → `UI` (spec §1).
    public static ProcessSource parse(String s) {
        return switch (s == null ? "" : s) {
            case "CODE" -> CODE;
            case "API" -> API;
            default -> UI;
        };
    }

    /// Whether the application sync may update or remove a row of this
    /// source (spec §7): `API` and `CODE` rows are sync-managed, `UI` rows
    /// are never touched.
    public boolean isSyncManaged() {
        return switch (this) {
            case CODE, API -> true;
            case UI -> false;
        };
    }
}

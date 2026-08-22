package io.flowcatalyst.platform.eventtype;

/// Where the event type was authored: `CODE` (legacy SDK source sync), `API`
/// (application sync — the only rows sync may delete) or `UI` (admin
/// create). The constant name is the stored and wire string.
public enum EventTypeSource {
    CODE, API, UI;

    /// Lenient reader for stored values: unknown → `UI` (spec §1).
    public static EventTypeSource parse(String s) {
        return switch (s == null ? "" : s) {
            case "CODE" -> CODE;
            case "API" -> API;
            default -> UI;
        };
    }
}

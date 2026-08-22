package io.flowcatalyst.platform.subscription;

/// Where the subscription was authored: `CODE` (legacy SDK source sync),
/// `API` (application sync) or `UI` (admin create). Sync updates and removes
/// `API`/`CODE` rows only; `UI` rows are never touched by sync (spec §7).
/// The constant name is the stored and wire string.
public enum SubscriptionSource {
    CODE, API, UI;

    /// Lenient reader for stored values: unknown → `UI` (spec §1).
    public static SubscriptionSource parse(String s) {
        return switch (s == null ? "" : s) {
            case "CODE" -> CODE;
            case "API" -> API;
            default -> UI;
        };
    }

    /// Whether sync may update or remove a row with this source (spec §7).
    public boolean isSyncManaged() {
        return switch (this) {
            case CODE, API -> true;
            case UI -> false;
        };
    }
}

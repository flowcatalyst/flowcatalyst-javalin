package io.flowcatalyst.platform.subscription;

/// Where the subscription was authored: `CODE` (legacy SDK source sync),
/// `API` (application sync) or `UI` (admin create). Sync updates and removes
/// `API`/`CODE` rows only; `UI` rows are never touched by sync (spec §7).
/// The constant name is the stored and wire string.
public enum SubscriptionSource {
    CODE, API, UI;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [SubscriptionRepository]'s row mapper, which wraps
    /// [UnrecognisedSubscriptionSourceException] in
    /// [CorruptSubscriptionException] carrying the row id.
    ///
    /// @throws UnrecognisedSubscriptionSourceException `s` is `null` or not
    ///                                                 one of `CODE` / `API` / `UI`
    public static SubscriptionSource parse(String s) {
        return switch (s) {
            case "CODE" -> CODE;
            case "API" -> API;
            case "UI" -> UI;
            case null, default -> throw new UnrecognisedSubscriptionSourceException(s);
        };
    }

    /// Whether sync may update or remove a row with this source (spec §7).
    public boolean isSyncManaged() {
        return switch (this) {
            case CODE, API -> true;
            case UI -> false;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedSubscriptionSourceException extends RuntimeException {
        public UnrecognisedSubscriptionSourceException(String raw) {
            super("unrecognised subscription source: " + raw);
        }
    }
}

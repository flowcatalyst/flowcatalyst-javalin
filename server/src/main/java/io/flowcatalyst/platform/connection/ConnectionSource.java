package io.flowcatalyst.platform.connection;

/// Where the connection was authored: `CODE` (SDK definition sync), `API`
/// (application sync) or `UI` (admin create). A connection sync updates and
/// removes `API`/`CODE` rows only; a `UI` row with the same key is left
/// untouched (spec `code-first-connections.md`, hand-off "Connection sync
/// (new)" — the same rule [io.flowcatalyst.platform.subscription.SubscriptionSource]
/// already enforces for subscriptions). The constant name is the stored and
/// wire string. Existing rows predate the concept and are backfilled `UI`
/// (V12 — there is no other way a `msg_connections` row comes to exist).
public enum ConnectionSource {
    CODE, API, UI;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [ConnectionRepository]'s row mapper, which wraps
    /// [UnrecognisedConnectionSourceException] in [CorruptConnectionException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedConnectionSourceException `s` is `null` or not
    ///                                                one of `CODE` / `API` / `UI`
    public static ConnectionSource parse(String s) {
        return switch (s) {
            case "CODE" -> CODE;
            case "API" -> API;
            case "UI" -> UI;
            case null, default -> throw new UnrecognisedConnectionSourceException(s);
        };
    }

    /// Whether a connection sync may update or remove a row with this source
    /// (hand-off "Connection sync (new)": a `UI`-authored row at the same key
    /// is left untouched).
    public boolean isSyncManaged() {
        return switch (this) {
            case CODE, API -> true;
            case UI -> false;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedConnectionSourceException extends RuntimeException {
        public UnrecognisedConnectionSourceException(String raw) {
            super("unrecognised connection source: " + raw);
        }
    }
}

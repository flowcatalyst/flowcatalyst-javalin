package io.flowcatalyst.platform.process;

/// Where the process was authored: `CODE` (the seeded catalogue), `API`
/// (application sync) or `UI` (admin create). The constant name is the
/// stored and wire string.
public enum ProcessSource {
    CODE, API, UI;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [ProcessRepository]'s row mapper, which wraps
    /// [UnrecognisedProcessSourceException] in [CorruptProcessException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedProcessSourceException `s` is `null` or not one
    ///                                            of `CODE` / `API` / `UI`
    public static ProcessSource parse(String s) {
        return switch (s) {
            case "CODE" -> CODE;
            case "API" -> API;
            case "UI" -> UI;
            case null, default -> throw new UnrecognisedProcessSourceException(s);
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

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedProcessSourceException extends RuntimeException {
        public UnrecognisedProcessSourceException(String raw) {
            super("unrecognised process source: " + raw);
        }
    }
}

package io.flowcatalyst.platform.eventtype;

/// Where the event type was authored: `CODE` (legacy SDK source sync), `API`
/// (application sync — the only rows sync may delete) or `UI` (admin
/// create). The constant name is the stored and wire string.
public enum EventTypeSource {
    CODE, API, UI;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [EventTypeRepository]'s row mapper, which wraps
    /// [UnrecognisedEventTypeSourceException] in [CorruptEventTypeException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedEventTypeSourceException `s` is `null` or not one
    ///                                              of `CODE` / `API` / `UI`
    public static EventTypeSource parse(String s) {
        return switch (s) {
            case "CODE" -> CODE;
            case "API" -> API;
            case "UI" -> UI;
            case null, default -> throw new UnrecognisedEventTypeSourceException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedEventTypeSourceException extends RuntimeException {
        public UnrecognisedEventTypeSourceException(String raw) {
            super("unrecognised event type source: " + raw);
        }
    }
}

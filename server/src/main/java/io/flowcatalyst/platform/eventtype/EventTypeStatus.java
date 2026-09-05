package io.flowcatalyst.platform.eventtype;

/// The event-type lifecycle state: `CURRENT` → `ARCHIVED` (one way). The
/// constant name is the stored and wire string.
public enum EventTypeStatus {
    CURRENT, ARCHIVED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [EventTypeRepository]'s row mapper, which wraps
    /// [UnrecognisedEventTypeStatusException] in [CorruptEventTypeException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedEventTypeStatusException `s` is `null` or not
    ///                                              `CURRENT` / `ARCHIVED`
    public static EventTypeStatus parse(String s) {
        return switch (s) {
            case "CURRENT" -> CURRENT;
            case "ARCHIVED" -> ARCHIVED;
            case null, default -> throw new UnrecognisedEventTypeStatusException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedEventTypeStatusException extends RuntimeException {
        public UnrecognisedEventTypeStatusException(String raw) {
            super("unrecognised event type status: " + raw);
        }
    }
}

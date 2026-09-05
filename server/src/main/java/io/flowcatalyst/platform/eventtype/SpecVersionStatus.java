package io.flowcatalyst.platform.eventtype;

/// The schema-version state machine: `FINALISING` → `CURRENT` → `DEPRECATED`
/// (spec §2). The constant name is the stored and wire string.
public enum SpecVersionStatus {
    FINALISING, CURRENT, DEPRECATED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [EventTypeRepository]'s row mapper, which wraps
    /// [UnrecognisedSpecVersionStatusException] in
    /// [CorruptEventTypeException] carrying the row id.
    ///
    /// @throws UnrecognisedSpecVersionStatusException `s` is `null` or not
    ///                                                one of the three states
    public static SpecVersionStatus parse(String s) {
        return switch (s) {
            case "FINALISING" -> FINALISING;
            case "CURRENT" -> CURRENT;
            case "DEPRECATED" -> DEPRECATED;
            case null, default -> throw new UnrecognisedSpecVersionStatusException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedSpecVersionStatusException extends RuntimeException {
        public UnrecognisedSpecVersionStatusException(String raw) {
            super("unrecognised spec version status: " + raw);
        }
    }
}

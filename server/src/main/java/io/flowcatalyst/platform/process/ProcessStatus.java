package io.flowcatalyst.platform.process;

/// The process lifecycle state: `CURRENT` → `ARCHIVED` (one way). The
/// constant name is the stored and wire string.
public enum ProcessStatus {
    CURRENT, ARCHIVED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [ProcessRepository]'s row mapper, which wraps
    /// [UnrecognisedProcessStatusException] in [CorruptProcessException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedProcessStatusException `s` is `null` or not
    ///                                            `CURRENT` / `ARCHIVED`
    public static ProcessStatus parse(String s) {
        return switch (s) {
            case "CURRENT" -> CURRENT;
            case "ARCHIVED" -> ARCHIVED;
            case null, default -> throw new UnrecognisedProcessStatusException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedProcessStatusException extends RuntimeException {
        public UnrecognisedProcessStatusException(String raw) {
            super("unrecognised process status: " + raw);
        }
    }
}

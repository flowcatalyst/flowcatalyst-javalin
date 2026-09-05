package io.flowcatalyst.platform.dispatchjob;

/// How a delivery attempt failed (spec §1.2). The constant name is the
/// stored and wire string.
public enum AttemptErrorType {
    CONNECTION, TIMEOUT, HTTP_ERROR, VALIDATION, UNKNOWN;

    /// Strict reader for stored values (spec §1.2, X-06): never a silent
    /// default. The repository maps a `NULL` column to "no error type"
    /// *before* calling this, so this never sees `null` in practice; a
    /// literal `UNKNOWN` is a recognised stored value like any other
    /// constant name, distinct from a genuinely unrecognised one. Only used
    /// for the stored column — nothing parses this enum from the wire. See
    /// [DispatchJobRepository]'s row mapper, which wraps
    /// [UnrecognisedAttemptErrorTypeException] in [CorruptDispatchJobException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedAttemptErrorTypeException `s` is `null` or not one
    ///                                               of the five constant names
    public static AttemptErrorType parse(String s) {
        return switch (s) {
            case "CONNECTION" -> CONNECTION;
            case "TIMEOUT" -> TIMEOUT;
            case "HTTP_ERROR" -> HTTP_ERROR;
            case "VALIDATION" -> VALIDATION;
            case "UNKNOWN" -> UNKNOWN;
            case null, default -> throw new UnrecognisedAttemptErrorTypeException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedAttemptErrorTypeException extends RuntimeException {
        public UnrecognisedAttemptErrorTypeException(String raw) {
            super("unrecognised attempt error type: " + raw);
        }
    }
}

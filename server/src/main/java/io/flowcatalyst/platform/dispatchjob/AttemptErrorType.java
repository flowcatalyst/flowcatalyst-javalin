package io.flowcatalyst.platform.dispatchjob;

/// How a delivery attempt failed (spec §1.2). The constant name is the
/// stored and wire string.
public enum AttemptErrorType {
    CONNECTION, TIMEOUT, HTTP_ERROR, VALIDATION, UNKNOWN;

    /// Lenient reader for stored values: unknown (and `null`) → `UNKNOWN`.
    /// The repository maps a `NULL` column to "no error type" *before*
    /// calling this, so `UNKNOWN` only ever means "a value we don't know".
    public static AttemptErrorType parse(String s) {
        return switch (s == null ? "" : s) {
            case "CONNECTION" -> CONNECTION;
            case "TIMEOUT" -> TIMEOUT;
            case "HTTP_ERROR" -> HTTP_ERROR;
            case "VALIDATION" -> VALIDATION;
            default -> UNKNOWN;
        };
    }
}

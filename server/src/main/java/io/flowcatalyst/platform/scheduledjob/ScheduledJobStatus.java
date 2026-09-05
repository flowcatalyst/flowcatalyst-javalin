package io.flowcatalyst.platform.scheduledjob;

/// The job lifecycle state (spec §2): `ACTIVE` ⇄ `PAUSED`, either →
/// `ARCHIVED`. The constant name is the stored and wire string.
public enum ScheduledJobStatus {
    ACTIVE, PAUSED, ARCHIVED;

    /// Strict reader for stored values (spec §1, X-06): never a silent
    /// default. See [ScheduledJobRepository]'s row mapper, which wraps
    /// [UnrecognisedScheduledJobStatusException] in
    /// [CorruptScheduledJobException] carrying the row id.
    ///
    /// @throws UnrecognisedScheduledJobStatusException `s` is `null` or not
    ///                                                 one of the three states
    public static ScheduledJobStatus parse(String s) {
        return switch (s) {
            case "ACTIVE" -> ACTIVE;
            case "PAUSED" -> PAUSED;
            case "ARCHIVED" -> ARCHIVED;
            case null, default -> throw new UnrecognisedScheduledJobStatusException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedScheduledJobStatusException extends RuntimeException {
        public UnrecognisedScheduledJobStatusException(String raw) {
            super("unrecognised scheduled job status: " + raw);
        }
    }
}

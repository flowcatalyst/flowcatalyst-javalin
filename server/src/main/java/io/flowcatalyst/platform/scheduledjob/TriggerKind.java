package io.flowcatalyst.platform.scheduledjob;

/// Why an instance was fired (spec §6.1): the poller (`CRON`), a human
/// (`MANUAL`) or a replay (`BACKFILL`). The constant name is the stored and
/// wire string.
public enum TriggerKind {
    CRON, MANUAL, BACKFILL;

    /// Strict reader for stored values (X-06): never a silent default. See
    /// [ScheduledJobInstanceRepository]'s row mapper, which wraps
    /// [UnrecognisedTriggerKindException] in [CorruptScheduledJobException]
    /// carrying the row id.
    ///
    /// @throws UnrecognisedTriggerKindException `s` is `null` or not one of
    ///                                          `CRON` / `MANUAL` / `BACKFILL`
    public static TriggerKind parse(String s) {
        return switch (s) {
            case "CRON" -> CRON;
            case "MANUAL" -> MANUAL;
            case "BACKFILL" -> BACKFILL;
            case null, default -> throw new UnrecognisedTriggerKindException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedTriggerKindException extends RuntimeException {
        public UnrecognisedTriggerKindException(String raw) {
            super("unrecognised trigger kind: " + raw);
        }
    }
}

package io.flowcatalyst.platform.scheduledjob;

/// Why an instance was fired (spec §6.1): the poller (`CRON`), a human
/// (`MANUAL`) or a replay (`BACKFILL`). The constant name is the stored and
/// wire string.
public enum TriggerKind {
    CRON, MANUAL, BACKFILL;

    /// Lenient reader for stored values: unknown → `CRON`.
    public static TriggerKind parse(String s) {
        return switch (s == null ? "" : s) {
            case "MANUAL" -> MANUAL;
            case "BACKFILL" -> BACKFILL;
            default -> CRON;
        };
    }
}

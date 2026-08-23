package io.flowcatalyst.platform.scheduledjob;

/// The job lifecycle state (spec §2): `ACTIVE` ⇄ `PAUSED`, either →
/// `ARCHIVED`. The constant name is the stored and wire string.
public enum ScheduledJobStatus {
    ACTIVE, PAUSED, ARCHIVED;

    /// Lenient reader for stored values: unknown → `ACTIVE` (spec §1).
    public static ScheduledJobStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "PAUSED" -> PAUSED;
            case "ARCHIVED" -> ARCHIVED;
            default -> ACTIVE;
        };
    }
}

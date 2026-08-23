package io.flowcatalyst.platform.dispatchjob;

/// What a dispatch job carries: a business `EVENT` or a `TASK` (spec §1.1).
/// The constant name is the stored and wire string.
public enum DispatchJobKind {
    EVENT, TASK;

    /// Lenient reader for stored values: unknown (and `null`) → `EVENT`.
    public static DispatchJobKind parse(String s) {
        return "TASK".equals(s) ? TASK : EVENT;
    }
}

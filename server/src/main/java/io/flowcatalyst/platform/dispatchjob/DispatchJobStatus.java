package io.flowcatalyst.platform.dispatchjob;

/// The dispatch-job lifecycle state (spec §2). The constant name is the
/// stored and wire string. Only [#PENDING] is written by this unit (requeue);
/// the other flips belong to the scheduler and the processing endpoint.
public enum DispatchJobStatus {
    PENDING, QUEUED, PROCESSING, COMPLETED, FAILED, CANCELLED, EXPIRED;

    /// Lenient reader for stored values: legacy aliases `IN_PROGRESS` →
    /// `PROCESSING`, `ERROR` → `FAILED`; unknown (and `null`) → `PENDING`
    /// (spec §1.1, open question 11).
    public static DispatchJobStatus parse(String s) {
        return switch (s == null ? "" : s) {
            case "QUEUED" -> QUEUED;
            case "PROCESSING", "IN_PROGRESS" -> PROCESSING;
            case "COMPLETED" -> COMPLETED;
            case "FAILED", "ERROR" -> FAILED;
            case "CANCELLED" -> CANCELLED;
            case "EXPIRED" -> EXPIRED;
            default -> PENDING;
        };
    }

    /// Whether the status will not change further on its own.
    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, EXPIRED -> true;
            case PENDING, QUEUED, PROCESSING -> false;
        };
    }
}

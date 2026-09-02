package io.flowcatalyst.platform.dispatchjob;

/// The dispatch-job lifecycle state (spec §2). The constant name is the
/// stored and wire string. Only [#PENDING] is written by this unit's requeue;
/// [#CANCELLED] and [#COMPLETED] are also reachable from `FAILED` via the
/// operator's cancel/complete verbs (dispatch-seam spec §8); the remaining
/// flips belong to the scheduler and the processing endpoint.
public enum DispatchJobStatus {
    PENDING, QUEUED, PROCESSING, COMPLETED, FAILED, CANCELLED, EXPIRED;

    /// Strict reader for stored values (dispatch-seam spec §4, X-06):
    /// legacy aliases `IN_PROGRESS` → `PROCESSING` and `ERROR` → `FAILED`
    /// are accepted deliberately — real values the column has held, not wire
    /// input — but any other value is rejected. A corrupted terminal status
    /// silently reappearing as `PENDING` could resurrect a job that already
    /// completed or failed, so there is no default here: callers MUST catch
    /// [UnrecognisedStatusException] and fail the read loudly (see
    /// [DispatchJobRepository]'s `toEntity`/`toProjection`, which wrap it in
    /// a [CorruptDispatchJobException] carrying the row id).
    ///
    /// @throws UnrecognisedStatusException `s` is `null` or not one of the
    ///                                     recognised/legacy values above
    public static DispatchJobStatus parse(String s) {
        return switch (s) {
            case "PENDING" -> PENDING;
            case "QUEUED" -> QUEUED;
            case "PROCESSING", "IN_PROGRESS" -> PROCESSING;
            case "COMPLETED" -> COMPLETED;
            case "FAILED", "ERROR" -> FAILED;
            case "CANCELLED" -> CANCELLED;
            case "EXPIRED" -> EXPIRED;
            case null, default -> throw new UnrecognisedStatusException(s);
        };
    }

    /// Whether the status will not change further on its own.
    public boolean isTerminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED, EXPIRED -> true;
            case PENDING, QUEUED, PROCESSING -> false;
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default.
    public static final class UnrecognisedStatusException extends RuntimeException {
        public UnrecognisedStatusException(String raw) {
            super("unrecognised dispatch job status: " + raw);
        }
    }
}

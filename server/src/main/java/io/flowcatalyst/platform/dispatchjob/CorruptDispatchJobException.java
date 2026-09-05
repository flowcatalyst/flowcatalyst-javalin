package io.flowcatalyst.platform.dispatchjob;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `msg_dispatch_jobs` / `msg_dispatch_jobs_read` whose
/// `status` column holds a value [DispatchJobStatus#parse] does not
/// recognise (dispatch-seam spec §4, X-06: never a silent default — a
/// corrupted terminal status silently reappearing as `PENDING` could
/// resurrect a job that already completed or failed). Carries the offending
/// row's id so an operator can find it. A list read that hits one corrupt
/// row fails the whole list, not just that row — [DispatchJobRepository]'s
/// jOOQ `.fetch(...)` propagates this naturally, since it is thrown from the
/// per-row mapper.
public final class CorruptDispatchJobException extends CorruptRowException {

    public CorruptDispatchJobException(String dispatchJobId, Throwable cause) {
        super("dispatch job " + dispatchJobId + " has a corrupt status: " + cause.getMessage(),
                "dispatch job", dispatchJobId, cause);
    }

    public String dispatchJobId() {
        return rowId();
    }
}

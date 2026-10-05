package io.flowcatalyst.platform.dispatchjob.processing;

import java.time.Instant;

/// The status transitions [ProcessingApi] makes on a job, extracted as a seam
/// (the same reasoning as [ProcessingRepository]): a test decorator can inject
/// a failure at exactly one of these calls.
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobLifecycle] implements this
/// — the lifecycle is the only writer of the status column.
public interface ProcessingTransitions {

    /// A live job → `PENDING` at `scheduledFor` (hold-back revert, cooperative deferral).
    void reschedule(String id, Instant createdAt, Instant scheduledFor);

    /// @return `true` when this call won the claim (exactly one row updated);
    /// `false` means another delivery already claimed (or finished) the job.
    boolean claimForDelivery(String id, Instant createdAt);

    void markCompleted(String id, Instant createdAt, Instant completedAt, Long durationMillis);

    void scheduleRetry(String id, Instant createdAt, Instant scheduledFor, int attemptCount, String lastError);

    void markFailed(String id, Instant createdAt, String lastError);
}

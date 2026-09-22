package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.Attempt;
import io.flowcatalyst.platform.dispatchjob.AttemptErrorType;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;

import java.time.Instant;
import java.util.Optional;

/// The handful of [io.flowcatalyst.platform.dispatchjob.DispatchJobRepository]
/// methods [ProcessingApi] calls, extracted as a seam (audit finding,
/// test-gap): a test decorator can inject a failure at exactly one of these
/// calls without mocking the whole repository or being able to make a real
/// database fail on demand — the three 500 `ack:false` branches (load
/// failure, `groupHeldBefore` failure, `reschedule`-while-held failure) had
/// no pinning test for want of a way to make any of them actually happen.
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobRepository] implements
/// this directly; production wiring is unaffected.
public interface ProcessingRepository {

    Optional<DispatchJob> findById(String id);

    boolean groupHeldBefore(DispatchJob job);

    void reschedule(String id, Instant createdAt, Instant scheduledFor);

    /// @return `true` when this call won the claim (exactly one row updated);
    /// `false` means another delivery already claimed (or finished) the job.
    boolean claimForDelivery(String id, Instant createdAt);

    void recordAttempt(String jobId, int attemptNumber, boolean success, Integer responseCode,
                        String responseBody, String errorMessage, AttemptErrorType errorType,
                        Attempt.RequestInfo requestInfo, Instant attemptedAt, Instant completedAt, Long durationMillis);

    void markCompleted(String id, Instant createdAt, Instant completedAt, Long durationMillis);

    void scheduleRetry(String id, Instant createdAt, Instant scheduledFor, int attemptCount, String lastError);

    void markFailed(String id, Instant createdAt, String lastError);
}

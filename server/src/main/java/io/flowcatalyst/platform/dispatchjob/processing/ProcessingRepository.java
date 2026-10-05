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
/// this directly. The handler's status transitions are the separate
/// [ProcessingTransitions] seam: the lifecycle owns every status write.
public interface ProcessingRepository {

    Optional<DispatchJob> findById(String id);

    boolean groupHeldBefore(DispatchJob job);

    void recordAttempt(String jobId, int attemptNumber, boolean success, Integer responseCode,
                        String responseBody, String errorMessage, AttemptErrorType errorType,
                        Attempt.RequestInfo requestInfo, Instant attemptedAt, Instant completedAt, Long durationMillis);
}

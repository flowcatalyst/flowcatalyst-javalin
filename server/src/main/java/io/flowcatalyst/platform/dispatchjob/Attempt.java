package io.flowcatalyst.platform.dispatchjob;

import java.time.Instant;
import java.util.Objects;

/// One delivery attempt against a job — a row of `msg_dispatch_job_attempts`
/// (spec §1.2). Recorded by the processing endpoint (a later unit); this
/// unit only reads the history, so there are no transitions.
///
/// `success` is derived from the stored `status` column (`SUCCESS` → true,
/// anything else → false); the string itself is never exposed.
///
/// @param attemptNumber  1-based attempt ordinal
/// @param attemptedAt    when the attempt started
/// @param completedAt    when it finished, `null` while in flight / unknown
/// @param durationMillis wall time, `null` when unknown
/// @param responseCode   HTTP status of the target's answer, `null` when none
/// @param responseBody   the (capped) response body, `null` when none
/// @param success        whether the target accepted the delivery
/// @param errorMessage   failure message, `null` on success
/// @param errorType      failure class, `null` on success / when not recorded
public record Attempt(
        int attemptNumber,
        Instant attemptedAt,
        Instant completedAt,
        Long durationMillis,
        Integer responseCode,
        String responseBody,
        boolean success,
        String errorMessage,
        AttemptErrorType errorType) {

    public Attempt {
        Objects.requireNonNull(attemptedAt, "attemptedAt");
    }
}

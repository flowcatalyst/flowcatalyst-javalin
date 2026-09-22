package io.flowcatalyst.platform.dispatchjob;

import java.time.Instant;
import java.util.List;
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
/// @param request        what the platform SENT on this attempt ([RequestInfo]),
///                        `null` on an attempt recorded before 2026-09-22
public record Attempt(
        int attemptNumber,
        Instant attemptedAt,
        Instant completedAt,
        Long durationMillis,
        Integer responseCode,
        String responseBody,
        boolean success,
        String errorMessage,
        AttemptErrorType errorType,
        RequestInfo request) {

    public Attempt {
        Objects.requireNonNull(attemptedAt, "attemptedAt");
    }

    /// What was SENT on a delivery attempt, recorded beside the subscriber's
    /// answer so a rejection can be read against the request that earned it
    /// (`docs/go-mirror/2026-09-22-delivery-credentials-handoff.md` addendum).
    /// **Never a secret**: [#signedBy] is the signing service account's
    /// *code*, never its key; [#headers] is header NAMES only, never a
    /// value — `X-FlowCatalyst-Signature`/`Authorization`'s value is never
    /// here even though their NAME is.
    ///
    /// @param signedBy       the signing service account's code, `null` when the delivery went out bare
    /// @param signature      whether `X-FlowCatalyst-Signature`/`-Timestamp` were sent
    /// @param bearer         whether `Authorization` was sent
    /// @param timestamp      the `X-FlowCatalyst-Timestamp` value the signature covers, `null` when unsigned
    /// @param headers        every header NAME sent, sorted
    /// @param unsignedReason why no credentials were attached, `null` unless BOTH `signature` and `bearer` are `false`
    /// @param target         the URL the request went to
    public record RequestInfo(
            String signedBy,
            boolean signature,
            boolean bearer,
            String timestamp,
            List<String> headers,
            String unsignedReason,
            String target) {
        public RequestInfo {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }
}

package io.flowcatalyst.platform.loginattempt;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// One row of the authentication trail (spec §1): the outcome of a password
/// login, a passkey login or a token mint, keyed on the identifier the
/// caller typed. Written once by the auth flows through
/// [LoginAttemptRepository#recordAttempt], never updated, and read by the
/// admin list and the brute-force backoff — so it has no transitions.
///
/// `failureReason`, `identifier`, `principalId`, `ipAddress` and `userAgent`
/// are `null` when absent (never `""`); the wire's `""` for a missing
/// identifier is the DTO's business.
public record LoginAttempt(
        String id,
        AttemptType attemptType,
        AttemptOutcome outcome,
        String failureReason,
        String identifier,
        String principalId,
        String ipAddress,
        String userAgent,
        Instant attemptedAt) implements HasId {

    public LoginAttempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(attemptType, "attemptType");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(attemptedAt, "attemptedAt");
    }

    /// A fresh attempt stamped `now`, with a generated `lat_` id. Every
    /// optional detail is `null` when the caller does not know it; the
    /// caller normalises the identifier (lower-case, trim) before recording
    /// so it agrees with the backoff queries (spec §5, open question 6).
    public static LoginAttempt attempt(AttemptType attemptType, AttemptOutcome outcome, String identifier,
                                       String principalId, String ipAddress, String userAgent, String failureReason) {
        return new LoginAttempt(EntityType.LOGIN_ATTEMPT.generate(), attemptType, outcome, failureReason,
                identifier, principalId, ipAddress, userAgent, Instant.now());
    }

    /// The keyset position of this row in the newest-first order (spec §4).
    public LoginAttemptCursor cursor() {
        return new LoginAttemptCursor(attemptedAt, id);
    }
}

package io.flowcatalyst.platform.loginattempt;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `iam_login_attempts` whose `attempt_type` or `outcome`
/// column holds a value [AttemptType#parse] / [AttemptOutcome#parse] does
/// not recognise (X-06: never a silent default — a corrupt `outcome`
/// defaulting to `SUCCESS` would reset the brute-force lockout window for a
/// login that never actually succeeded). Carries the offending row's id.
/// [LoginAttemptRepository]'s row mapper and `lastSuccessAt` both throw this
/// instead of returning a coerced entity or a falsely-empty result.
public final class CorruptLoginAttemptException extends CorruptRowException {

    public CorruptLoginAttemptException(String attemptId, Throwable cause) {
        super("login attempt", attemptId, cause);
    }
}

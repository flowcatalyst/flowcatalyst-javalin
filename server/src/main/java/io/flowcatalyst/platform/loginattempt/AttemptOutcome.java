package io.flowcatalyst.platform.loginattempt;

/// Whether the attempt succeeded (spec §1). The constant name is the stored
/// and wire string.
public enum AttemptOutcome {
    SUCCESS, FAILURE;

    /// Strict reader for stored values (X-06): a corrupt `outcome` must
    /// never silently read as `SUCCESS` — that would reset the brute-force
    /// lockout window for a row that was never actually a successful login.
    /// See [LoginAttemptRepository]'s row mapper and `lastSuccessAt`, which
    /// wrap this in [CorruptLoginAttemptException] carrying the row id.
    ///
    /// @throws UnrecognisedOutcomeException `s` is `null` or neither
    ///                                      `SUCCESS` nor `FAILURE`
    public static AttemptOutcome parse(String s) {
        return switch (s) {
            case "SUCCESS" -> SUCCESS;
            case "FAILURE" -> FAILURE;
            case null, default -> throw new UnrecognisedOutcomeException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised set —
    /// X-06: never a silent default (a security bug when the default is
    /// `SUCCESS`).
    public static final class UnrecognisedOutcomeException extends RuntimeException {
        public UnrecognisedOutcomeException(String raw) {
            super("unrecognised login attempt outcome: " + raw);
        }
    }
}

package io.flowcatalyst.platform.auth.mfa;

import java.time.Instant;

/// One row of `iam_mfa_email_pins` (§3.4, §11.5). `purpose` is `login` or
/// `enroll`; `attempts` counts wrong guesses; the row is deleted on
/// success, on the fifth wrong guess, and when found expired.
public record EmailPin(String id, String principalId, String purpose, String pinHash, int attempts,
                       Instant expiresAt, Instant createdAt) {

    public static final String LOGIN = "login";
    public static final String ENROLL = "enroll";

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}

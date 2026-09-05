package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;

import java.time.Instant;

/// One row of `iam_user_mfa_methods` (`docs/spec/auth-identity.md` §3.4,
/// §11.4): unique per (principal, method); `confirmedAt == null` is a
/// pending enrolment that never satisfies a challenge; `lastUsedAt` is the
/// TOTP replay guard (the accepted step's representative time).
public record MfaMethodRow(String id, String principalId, MfaMethod method, String secretEncrypted,
                           Instant confirmedAt, Instant lastUsedAt, Instant createdAt) {

    public boolean confirmed() {
        return confirmedAt != null;
    }
}

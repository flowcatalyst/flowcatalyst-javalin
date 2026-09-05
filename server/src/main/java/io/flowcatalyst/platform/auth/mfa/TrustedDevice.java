package io.flowcatalyst.platform.auth.mfa;

import java.time.Instant;

/// One row of `iam_mfa_trusted_devices` (§3.4, §11.7). Only the SHA-256 of
/// the cookie value is stored, so a database read cannot mint a cookie.
/// The wire shape (ruling I-Q21) is `id, label?, expiresAt, createdAt,
/// lastUsedAt?` in the platform time format — no `principalId`.
public record TrustedDevice(String id, String principalId, String label, Instant expiresAt, Instant createdAt,
                            Instant lastUsedAt) {
}

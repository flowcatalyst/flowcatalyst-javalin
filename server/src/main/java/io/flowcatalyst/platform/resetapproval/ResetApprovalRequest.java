package io.flowcatalyst.platform.resetapproval;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/// The lost-device reset-approval aggregate root (`docs/spec/auth-identity.md`
/// §3.7, §8.6, §11.10, and the §0.5 rulings — defect 11: `EXPIRED` is
/// written by the purger and the reviewer's `note` is persisted; Q22: the
/// system actor is `"system"`). Queued when a user without a strong second
/// factor asks for a password reset (ruling I-Q19 keeps
/// `requireStrongFactorForReset = false` in production, so the queue is
/// idle — this is feature parity, not dead code); a client administrator
/// then approves (mailing a reset link) or denies it.
///
/// Immutable record: [#approve] / [#deny] return a copy and throw
/// [UseCaseException] when the request is not `PENDING` and unexpired. The
/// repository's own guarded `UPDATE … WHERE status = 'PENDING' AND
/// expires_at > now()` ([ResetApprovalRepository#decide]) is the actual
/// concurrency guard the operation relies on — these transitions exist so
/// the invariant is pinned and testable without a database
/// ([ResetApprovalRequestTest]).
///
/// @param id         `rar_…` TSID
/// @param principalId the user the reset is for
/// @param clientId    the user's home client; `null` only ever transiently —
///                    the queue never creates a row for a principal without one
/// @param status      `PENDING` \| `APPROVED` \| `DENIED` \| `EXPIRED`
/// @param reset2fa    always `true` from [#create] (spec §3.7)
/// @param note        the reviewer's note; `null` until decided or when none was given
/// @param decidedBy   the deciding admin's principal id; `null` until decided
/// @param decidedAt   when it was decided; `null` until decided
/// @param expiresAt   `createdAt` + 72 hours
/// @param createdAt   when the request was queued
public record ResetApprovalRequest(
        String id,
        String principalId,
        String clientId,
        ResetApprovalStatus status,
        boolean reset2fa,
        String note,
        String decidedBy,
        Instant decidedAt,
        Instant expiresAt,
        Instant createdAt) implements HasId {

    /// Spec §3.7: the approval window.
    public static final Duration TTL = Duration.ofHours(72);

    public ResetApprovalRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// A fresh `PENDING` request, `reset2fa = true`, expiring in [#TTL].
    public static ResetApprovalRequest create(String principalId, String clientId) {
        Objects.requireNonNull(principalId, "principalId");
        Instant now = Instant.now();
        return new ResetApprovalRequest(EntityType.RESET_APPROVAL_REQUEST.generate(), principalId, clientId,
                ResetApprovalStatus.PENDING, true, null, null, null, now.plus(TTL), now);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /// `PENDING` and not yet expired — the only state [#approve] / [#deny] accept from.
    public boolean isPending(Instant now) {
        return status == ResetApprovalStatus.PENDING && !isExpired(now);
    }

    /// @throws UseCaseException validation `ALREADY_DECIDED` unless [#isPending]
    public ResetApprovalRequest approve(String by, String note, Instant now) {
        return decide(ResetApprovalStatus.APPROVED, by, note, now);
    }

    /// @throws UseCaseException validation `ALREADY_DECIDED` unless [#isPending]
    public ResetApprovalRequest deny(String by, String note, Instant now) {
        return decide(ResetApprovalStatus.DENIED, by, note, now);
    }

    private ResetApprovalRequest decide(ResetApprovalStatus outcome, String by, String note, Instant now) {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(now, "now");
        if (!isPending(now)) {
            throw UseCaseException.validation("ALREADY_DECIDED", "request is no longer pending");
        }
        return new ResetApprovalRequest(id, principalId, clientId, outcome, reset2fa, note, by, now, expiresAt, createdAt);
    }
}

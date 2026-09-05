package io.flowcatalyst.platform.resetapproval;

/// `PENDING` (created by the queue, awaiting a decision), `APPROVED` /
/// `DENIED` (an admin decided), `EXPIRED` (the purger swept it past
/// `expires_at` — ruling defect 11, `docs/spec/auth-identity.md` §3.7,
/// §11.10: the column is declared but Go never wrote it; the Java purger,
/// [io.flowcatalyst.platform.purger.AuthHousekeeping#expirePendingApprovalRequests],
/// does). The constant name is the stored/wire string.
public enum ResetApprovalStatus {
    PENDING, APPROVED, DENIED, EXPIRED;

    /// @throws UnrecognisedResetApprovalStatusException `s` is `null`, blank,
    ///                                                  or not one of the constants above
    public static ResetApprovalStatus parse(String s) {
        return switch (s) {
            case "PENDING" -> PENDING;
            case "APPROVED" -> APPROVED;
            case "DENIED" -> DENIED;
            case "EXPIRED" -> EXPIRED;
            case null, default -> throw new UnrecognisedResetApprovalStatusException(s);
        };
    }

    /// Thrown by [#parse] for a value outside the recognised set — never a silent default.
    public static final class UnrecognisedResetApprovalStatusException extends RuntimeException {
        public UnrecognisedResetApprovalStatusException(String raw) {
            super("unrecognised reset-approval status: " + raw);
        }
    }
}

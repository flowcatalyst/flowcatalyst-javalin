package io.flowcatalyst.platform.resetapproval.operations;

import io.flowcatalyst.platform.resetapproval.ResetApprovalStatus;

/// `DecideResetApproval`'s command (spec §8.6). `status` is always
/// `APPROVED` or `DENIED` — chosen by which route the admin called, never
/// parsed from the request body. `note` is the optional reviewer note
/// (defect 11).
public record DecideCommand(String id, ResetApprovalStatus status, String note) {
}

package io.flowcatalyst.platform.resetapproval.operations;

import io.flowcatalyst.platform.resetapproval.ResetApprovalRepository;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRequest;
import io.flowcatalyst.platform.resetapproval.operations.ResetApprovalEvents.ResetApprovalQueued;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Inserts a fresh `PENDING` request and emits [ResetApprovalQueued] (spec
/// §8.6). The "principal has no client" and "an unexpired request already
/// exists" no-ops are **not** modelled here — the Go behaviour is silent
/// success either way, with nothing to validate, plan, or audit, so those
/// checks live in the seam that decides whether to call this operation at
/// all: [io.flowcatalyst.platform.resetapproval.ResetApprovalQueue#queue].
/// This operation always creates a row when run. System-actor only (Q22) —
/// there is no HTTP entry point, so `Authorize.publicAccess()`.
public final class QueueResetApproval {

    private QueueResetApproval() {
    }

    public static Operation<QueueCommand, ResetApprovalQueued> of(ResetApprovalRepository repo) {
        return Operation.<QueueCommand, ResetApprovalQueued>named("QueueResetApproval")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.principalId(), "PRINCIPAL_ID_REQUIRED", "principalId is required"))
                .authorize(Operation.Authorize.publicAccess()) // system actor only; no external caller (spec §8.6, Q22)
                .execute((cmd, ec) -> {
                    ResetApprovalRequest created = ResetApprovalRequest.create(cmd.principalId(), cmd.clientId());
                    return Plan.save(created, repo, ResetApprovalQueued.of(ec, created));
                });
    }
}

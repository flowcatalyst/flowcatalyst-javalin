package io.flowcatalyst.platform.resetapproval.operations;

import io.flowcatalyst.platform.resetapproval.ResetApprovalRepository;
import io.flowcatalyst.platform.resetapproval.ResetApprovalRequest;
import io.flowcatalyst.platform.resetapproval.operations.ResetApprovalEvents.ResetApprovalDecided;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;

/// Approves or denies a request (spec §8.6, §11.10). The resource-level
/// gate (`Checks.requireUserAdmin(ac, request.clientId())`) runs in the API
/// handler, which must load the row anyway to answer 404 before this
/// operation is even called — so `Authorize.publicAccess()` here, per
/// CONVENTIONS §3's note that a coarse-plus-resource check already applied
/// by an entry point that had to load the row first is that entry point's
/// job, not a re-derivation inside `operations/`.
///
/// The actual decision is [ResetApprovalRepository#decide] — a single
/// guarded `UPDATE`, run outside this operation's [Plan] on purpose (see
/// its doc): a `0` row count is the "already decided" outcome and is
/// thrown here as the business-rule 400 the spec calls for, before any
/// [Plan] is built, so nothing is emitted for a decision that did not
/// actually happen.
public final class DecideResetApproval {

    private DecideResetApproval() {
    }

    public static Operation<DecideCommand, ResetApprovalDecided> of(ResetApprovalRepository repo) {
        return Operation.<DecideCommand, ResetApprovalDecided>named("DecideResetApproval")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.status() == null) {
                        throw UseCaseException.validation("STATUS_REQUIRED", "status is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // resource-level gate already run by the handler (see class doc)
                .execute((cmd, ec) -> {
                    ResetApprovalRequest existing = repo.findById(cmd.id())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("ResetApprovalRequest", cmd.id()));
                    Instant now = Instant.now();
                    int rows = repo.decide(cmd.id(), cmd.status(), ec.principalId(), cmd.note(), now);
                    if (rows == 0) {
                        throw UseCaseException.validation("ALREADY_DECIDED", "request is no longer pending");
                    }
                    return Plan.emit(ResetApprovalDecided.of(ec, existing, cmd.status(), ec.principalId()));
                });
    }
}

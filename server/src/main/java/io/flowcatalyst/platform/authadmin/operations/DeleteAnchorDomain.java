package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.AnchorDomain;
import io.flowcatalyst.platform.authadmin.AnchorDomainRepository;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AnchorDomainDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an anchor domain and emits [AnchorDomainDeleted] (spec §4.1).
public final class DeleteAnchorDomain {

    private DeleteAnchorDomain() {
    }

    public static Operation<DeleteAnchorDomainCommand, AnchorDomainDeleted> of(AnchorDomainRepository repo) {
        return Operation.<DeleteAnchorDomainCommand, AnchorDomainDeleted>named("DeleteAnchorDomain")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Anchor domains are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    AnchorDomain a = Access.anchorDomainById(repo, cmd.id());
                    return Plan.delete(a, repo, AnchorDomainDeleted.of(ec, a));
                });
    }
}

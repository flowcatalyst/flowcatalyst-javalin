package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a portal identity (spec `auth-identity.md` §5.7:
/// offboarding). `Authorize: Public` — the admin API's controller gates.
/// Same cross-client-hidden 404 rule as [SetPortalIdentityStatus].
public final class DeletePortalIdentity {

    private DeletePortalIdentity() {
    }

    public static Operation<DeleteCommand, PortalIdentityDeleted> of(PortalIdentityRepository repo) {
        return Operation.<DeleteCommand, PortalIdentityDeleted>named("DeletePortalIdentity")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    PortalIdentity existing = repo.findById(cmd.id())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("PortalIdentity", cmd.id()));
                    if (cmd.clientId() != null && !cmd.clientId().isBlank() && !cmd.clientId().equals(existing.clientId())) {
                        throw UseCaseException.resourceNotFound("PortalIdentity", cmd.id());
                    }
                    return Plan.delete(existing, repo, PortalIdentityDeleted.of(ec, existing));
                });
    }
}

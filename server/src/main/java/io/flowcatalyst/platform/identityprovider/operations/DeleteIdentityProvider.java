package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.operations.IdentityProviderEvents.IdentityProviderDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a provider and emits [IdentityProviderDeleted]. The
/// aggregate refuses while email domains still route to it (a dangling
/// mapping would silently flip its users to the password prompt) and
/// refuses the seeded internal provider outright (spec §2).
public final class DeleteIdentityProvider {

    private DeleteIdentityProvider() {
    }

    public static Operation<DeleteCommand, IdentityProviderDeleted> of(IdentityProviderRepository repo) {
        return Operation.<DeleteCommand, IdentityProviderDeleted>named("DeleteIdentityProvider")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Identity providers are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    IdentityProvider ip = Access.byId(repo, cmd.id()).requireDeletable();
                    return Plan.delete(ip, repo, IdentityProviderDeleted.of(ec, ip));
                });
    }
}

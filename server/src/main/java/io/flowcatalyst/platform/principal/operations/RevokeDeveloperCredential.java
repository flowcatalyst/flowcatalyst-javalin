package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.DeveloperCredentialRevoked;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Clears a principal's developer client-secret without touching its role
/// (kill a leaked secret, set a fresh one later) and emits
/// [DeveloperCredentialRevoked]. Self-or-user-admin post-load.
public final class RevokeDeveloperCredential {

    private RevokeDeveloperCredential() {
    }

    public static Operation<RevokeDeveloperCredentialCommand, DeveloperCredentialRevoked> of(PrincipalRepository repo) {
        return Operation.<RevokeDeveloperCredentialCommand, DeveloperCredentialRevoked>named("RevokeDeveloperCredential")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.principalId(), "PRINCIPAL_ID_REQUIRED", "Principal ID is required"))
                .authorize(Operation.Authorize.publicAccess()) // self-or-admin runs post-load: Access.requireSelfOrUserAdmin
                .execute((cmd, ec) -> {
                    Principal p = Access.loadUser(repo, cmd.principalId());
                    Access.requireSelfOrUserAdmin(p);
                    p = p.clearDeveloperSecret();
                    return Plan.save(p, repo, DeveloperCredentialRevoked.of(ec, p));
                });
    }
}

package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.ClientAccessGrant;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.ClientAccessRevoked;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Removes a PARTNER user's client-access grant and emits
/// [ClientAccessRevoked] (spec §6). [Operation.Authorize#publicAccess()]:
/// the only caller is the anchor-gated revoke route; the operation enforces
/// the domain invariants (USER, grant exists).
public final class RevokeClientAccess {

    private RevokeClientAccess() {
    }

    public static Operation<RevokeClientAccessCommand, ClientAccessRevoked> of(PrincipalRepository repo, ClientAccessGrantRepository grants) {
        return Operation.<RevokeClientAccessCommand, ClientAccessRevoked>named("RevokeClientAccess")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.userId(), "USER_ID_REQUIRED", "User ID is required");
                    UseCaseException.requireNonBlank(cmd.clientId(), "CLIENT_ID_REQUIRED", "Client ID is required");
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Principal p = Access.loadUser(repo, cmd.userId());
                    if (!p.isUser()) {
                        throw UseCaseException.businessRule("NOT_A_USER", "Client access can only be revoked from USER type principals");
                    }
                    ClientAccessGrant grant = grants.findByPrincipalAndClient(cmd.userId(), cmd.clientId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("Grant", cmd.userId() + ":" + cmd.clientId()));
                    return Plan.delete(grant, grants, ClientAccessRevoked.of(ec, p, cmd.clientId()));
                });
    }
}

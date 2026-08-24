package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.ClientAccessGrant;
import io.flowcatalyst.platform.principal.ClientAccessGrantRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.UserScope;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.ClientAccessGranted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Records a PARTNER user's access to one client — a new
/// [ClientAccessGrant] aggregate — and emits [ClientAccessGranted] (spec §6).
///
/// Authorization is [Operation.Authorize#publicAccess()]: the operation has
/// no per-resource dimension of its own — every caller is an admin handler
/// that already gated it (the grant route with `requireAnchor`; the
/// create-user partner-merge with `requireUserAdmin`). It enforces only the
/// domain invariants: USER, PARTNER, client exists, no duplicate grant.
public final class GrantClientAccess {

    private GrantClientAccess() {
    }

    public static Operation<GrantClientAccessCommand, ClientAccessGranted> of(PrincipalRepository repo, ClientRepository clients,
                                                                              ClientAccessGrantRepository grants) {
        return Operation.<GrantClientAccessCommand, ClientAccessGranted>named("GrantClientAccess")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.userId(), "USER_ID_REQUIRED", "User ID is required");
                    UseCaseException.requireNonBlank(cmd.clientId(), "CLIENT_ID_REQUIRED", "Client ID is required");
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Principal p = Access.loadUser(repo, cmd.userId());
                    if (!p.isUser()) {
                        throw UseCaseException.businessRule("NOT_A_USER", "Client access can only be granted to USER type principals");
                    }
                    if (p.scope() != UserScope.PARTNER) {
                        throw UseCaseException.businessRule("NOT_PARTNER_SCOPE", "Client access grants are only for PARTNER scope users");
                    }
                    if (clients.findById(cmd.clientId()).isEmpty()) {
                        throw UseCaseException.resourceNotFound("Client", cmd.clientId());
                    }
                    if (grants.findByPrincipalAndClient(cmd.userId(), cmd.clientId()).isPresent()) {
                        throw UseCaseException.businessRule("GRANT_EXISTS", "User already has access to this client");
                    }
                    ClientAccessGrant grant = ClientAccessGrant.create(p.id(), cmd.clientId(), ec.principalId());
                    return Plan.save(grant, grants, ClientAccessGranted.of(ec, p, cmd.clientId()));
                });
    }
}

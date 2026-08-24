package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Changes a user's scope + client association with explicit intent (spec
/// §2): `*` → anchor; a specific client with `CHANGE_CLIENT` → new home
/// client; with `TO_PARTNER` → partner, the old home client (and the new
/// one) written as grants atomically with the scope change. Emits
/// [UserUpdated] (spec §8, open question 5).
///
/// [Operation.Authorize#publicAccess()]: the only caller is the anchor-gated
/// route — scope/client changes are anchor-only, so there is no
/// per-resource dimension to add here.
public final class SetClientAssociation {

    private SetClientAssociation() {
    }

    public static Operation<SetClientAssociationCommand, UserUpdated> of(PrincipalRepository repo, ClientRepository clients) {
        return Operation.<SetClientAssociationCommand, UserUpdated>named("SetClientAssociation")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.userId(), "USER_ID_REQUIRED", "User ID is required");
                    UseCaseException.requireNonBlank(cmd.clientId(), "CLIENT_ID_REQUIRED", "clientId is required (use \"*\" for anchor)");
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    String clientId = cmd.clientId().trim();
                    Principal p = Access.loadUser(repo, cmd.userId());
                    if (!p.isUser()) {
                        throw UseCaseException.businessRule("NOT_A_USER", "Client association only applies to USER principals");
                    }
                    Principal.ClientAssociationChanged change;
                    if (Principal.ANCHOR_CLIENT_WILDCARD.equals(clientId)) {
                        change = p.toAnchor();
                    } else if (cmd.mode() == null) {
                        throw UseCaseException.validation("MODE_REQUIRED",
                                "mode must be CHANGE_CLIENT or TO_PARTNER for a specific clientId (use \"*\" for anchor)");
                    } else {
                        if (clients.findById(clientId).isEmpty()) {
                            throw UseCaseException.resourceNotFound("Client", clientId);
                        }
                        change = switch (cmd.mode()) {
                            case CHANGE_CLIENT -> p.changeClient(clientId);
                            case TO_PARTNER -> p.toPartner(clientId);
                        };
                    }
                    return Plan.save(change.principal(), repo.withClientGrants(change.grantClientIds(), ec.principalId()),
                            UserUpdated.of(ec, change.principal()));
                });
    }
}

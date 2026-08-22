package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientActivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `ACTIVE`, clearing any suspension reason ([Client#activate]) and emits [ClientActivated].
public final class ActivateClient {

    private ActivateClient() {
    }

    public static Operation<ActivateCommand, ClientActivated> of(ClientRepository repo) {
        return Operation.<ActivateCommand, ClientActivated>named("ActivateClient")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Clients are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Client c = Access.byId(repo, cmd.id()).activate();
                    return Plan.save(c, repo, ClientActivated.of(ec, c));
                });
    }
}

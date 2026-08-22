package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a client row and emits [ClientDeleted]. Reached from both
/// `DELETE /{id}` and the `deactivate` alias; each keeps its own gate.
public final class DeleteClient {

    private DeleteClient() {
    }

    public static Operation<DeleteCommand, ClientDeleted> of(ClientRepository repo) {
        return Operation.<DeleteCommand, ClientDeleted>named("DeleteClient")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Clients are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Client c = Access.byId(repo, cmd.id());
                    return Plan.delete(c, repo, ClientDeleted.of(ec, c));
                });
    }
}

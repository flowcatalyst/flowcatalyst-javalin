package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientDeactivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `INACTIVE` with the optional reason ([Client#deactivate]) and emits
/// [ClientDeactivated]: the soft delete behind `POST /api/clients/{id}/deactivate`
/// (bug 15 — it ran [DeleteClient], which removed the row, while the SPA
/// promises "Can be reactivated later"). [DeleteClient] is the irreversible
/// removal.
public final class DeactivateClient {

    private DeactivateClient() {
    }

    public static Operation<DeactivateCommand, ClientDeactivated> of(ClientRepository repo) {
        return Operation.<DeactivateCommand, ClientDeactivated>named("DeactivateClient")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Clients are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    String reason = cmd.reason() == null || cmd.reason().isBlank() ? null : cmd.reason().strip();
                    Client c = Access.byId(repo, cmd.id()).deactivate(reason);
                    return Plan.save(c, repo, ClientDeactivated.of(ec, c, reason));
                });
    }
}

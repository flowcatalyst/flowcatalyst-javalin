package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Renames a client when a name is supplied and emits [ClientUpdated]. The
/// identifier is immutable. A command without a name still re-persists and
/// emits (spec §3, open question 4).
public final class UpdateClient {

    private UpdateClient() {
    }

    public static Operation<UpdateCommand, ClientUpdated> of(ClientRepository repo) {
        return Operation.<UpdateCommand, ClientUpdated>named("UpdateClient")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                })
                // Clients are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Client c = Access.byId(repo, cmd.id());
                    if (cmd.name() != null) {
                        c = c.rename(cmd.name());
                    }
                    return Plan.save(c, repo, ClientUpdated.of(ec, c));
                });
    }
}

package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a client (unique by normalised identifier) and emits [ClientCreated].
public final class CreateClient {

    private CreateClient() {
    }

    public static Operation<CreateCommand, ClientCreated> of(ClientRepository repo) {
        return Operation.<CreateCommand, ClientCreated>named("CreateClient")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                    ClientIdentifier.parse(cmd.identifier());
                })
                // Clients are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    ClientIdentifier identifier = ClientIdentifier.parse(cmd.identifier());
                    if (repo.findByIdentifier(identifier.value()).isPresent()) {
                        throw UseCaseException.conflict("IDENTIFIER_EXISTS",
                                "Client with identifier '" + identifier.value() + "' already exists");
                    }
                    Client c = Client.create(cmd.name(), identifier);
                    return Plan.save(c, repo, ClientCreated.of(ec, c));
                });
    }
}

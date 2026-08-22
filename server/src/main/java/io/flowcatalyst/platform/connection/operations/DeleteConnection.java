package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a connection and emits [ConnectionDeleted].
public final class DeleteConnection {

    private DeleteConnection() {
    }

    public static Operation<DeleteCommand, ConnectionDeleted> of(ConnectionRepository repo) {
        return Operation.<DeleteCommand, ConnectionDeleted>named("DeleteConnection")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Connection c = Access.loadScoped(repo, cmd.id());
                    return Plan.delete(c, repo, ConnectionDeleted.of(ec, c));
                });
    }
}

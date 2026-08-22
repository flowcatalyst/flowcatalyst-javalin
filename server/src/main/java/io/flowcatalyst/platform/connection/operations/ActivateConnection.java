package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `ACTIVE` ([Connection#activate], idempotent) and emits [ConnectionUpdated].
public final class ActivateConnection {

    private ActivateConnection() {
    }

    public static Operation<ActivateCommand, ConnectionUpdated> of(ConnectionRepository repo) {
        return Operation.<ActivateCommand, ConnectionUpdated>named("ActivateConnection")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Connection c = Access.loadScoped(repo, cmd.id()).activate();
                    return Plan.save(c, repo, ConnectionUpdated.of(ec, c));
                });
    }
}

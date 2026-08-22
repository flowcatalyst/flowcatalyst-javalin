package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `PAUSED` ([Connection#pause], idempotent) and emits [ConnectionUpdated].
public final class PauseConnection {

    private PauseConnection() {
    }

    public static Operation<PauseCommand, ConnectionUpdated> of(ConnectionRepository repo) {
        return Operation.<PauseCommand, ConnectionUpdated>named("PauseConnection")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Connection c = Access.loadScoped(repo, cmd.id()).pause();
                    return Plan.save(c, repo, ConnectionUpdated.of(ec, c));
                });
    }
}

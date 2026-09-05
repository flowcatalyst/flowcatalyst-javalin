package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.connection.ConnectionStatus;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents.ConnectionUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces name, description and external id — and optionally the status —
/// on an existing connection and emits [ConnectionUpdated]. The code is
/// immutable.
public final class UpdateConnection {

    private UpdateConnection() {
    }

    public static Operation<UpdateCommand, ConnectionUpdated> of(ConnectionRepository repo) {
        return Operation.<UpdateCommand, ConnectionUpdated>named("UpdateConnection")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "Connection name is required");
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Connection c = Access.loadScoped(repo, cmd.id())
                            .withName(cmd.name())
                            .withDescription(cmd.description())
                            .withExternalId(cmd.externalId());
                    if (cmd.status() != null) {
                        // The status field selects one of the two transitions (spec §4, open question 4).
                        c = switch (ConnectionStatus.parseCommandStatus(cmd.status())) {
                            case PAUSED -> c.pause();
                            case ACTIVE -> c.activate();
                        };
                    }
                    return Plan.save(c, repo, ConnectionUpdated.of(ec, c));
                });
    }
}

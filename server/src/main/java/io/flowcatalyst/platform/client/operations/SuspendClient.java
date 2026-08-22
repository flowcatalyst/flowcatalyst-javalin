package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.operations.ClientEvents.ClientSuspended;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `SUSPENDED` with a reason ([Client#suspend]) and emits [ClientSuspended].
public final class SuspendClient {

    private SuspendClient() {
    }

    public static Operation<SuspendCommand, ClientSuspended> of(ClientRepository repo) {
        return Operation.<SuspendCommand, ClientSuspended>named("SuspendClient")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    UseCaseException.requireNonBlank(cmd.reason(), "REASON_REQUIRED", "reason is required");
                })
                // Clients are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Client c = Access.byId(repo, cmd.id()).suspend(cmd.reason());
                    return Plan.save(c, repo, ClientSuspended.of(ec, c, cmd.reason()));
                });
    }
}

package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.ClientAuthConfig;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AuthConfigDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an auth config and emits [AuthConfigDeleted] (spec §4.2).
public final class DeleteAuthConfig {

    private DeleteAuthConfig() {
    }

    public static Operation<DeleteAuthConfigCommand, AuthConfigDeleted> of(ClientAuthConfigRepository repo) {
        return Operation.<DeleteAuthConfigCommand, AuthConfigDeleted>named("DeleteAuthConfig")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                // Auth configs are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    ClientAuthConfig c = Access.authConfigById(repo, cmd.id());
                    return Plan.delete(c, repo, AuthConfigDeleted.of(ec, c));
                });
    }
}

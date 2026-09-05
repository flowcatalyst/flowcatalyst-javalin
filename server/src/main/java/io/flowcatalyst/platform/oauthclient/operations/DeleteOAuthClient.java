package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes an OAuth client with its five junctions and emits
/// [OAuthClientDeleted]. Platform-level config (`Authorize.publicAccess()`);
/// the controller gates anchor-only.
public final class DeleteOAuthClient {

    private DeleteOAuthClient() {
    }

    public static Operation<DeleteOAuthClientCommand, OAuthClientDeleted> of(OAuthClientRepository repo) {
        return Operation.<DeleteOAuthClientCommand, OAuthClientDeleted>named("DeleteOAuthClient")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "id is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    OAuthClient c = Access.byId(repo, cmd.id());
                    return Plan.delete(c, repo, OAuthClientDeleted.of(ec, c));
                });
    }
}

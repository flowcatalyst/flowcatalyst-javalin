package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientActivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Flips the client active (idempotent, spec §8.5) and emits
/// [OAuthClientActivated]. Platform-level config (`Authorize.publicAccess()`);
/// the controller gates anchor-only.
public final class ActivateOAuthClient {

    private ActivateOAuthClient() {
    }

    public static Operation<ActivateCommand, OAuthClientActivated> of(OAuthClientRepository repo) {
        return Operation.<ActivateCommand, OAuthClientActivated>named("ActivateOAuthClient")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "id is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    OAuthClient c = Access.byId(repo, cmd.id()).activate();
                    return Plan.save(c, repo, OAuthClientActivated.of(ec, c));
                });
    }
}

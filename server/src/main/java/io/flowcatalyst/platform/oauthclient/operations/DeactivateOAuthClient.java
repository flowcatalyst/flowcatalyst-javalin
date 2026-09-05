package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientDeactivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Flips the client inactive (idempotent, spec §8.5) and emits
/// [OAuthClientDeactivated]. Platform-level config (`Authorize.publicAccess()`);
/// the controller gates anchor-only.
public final class DeactivateOAuthClient {

    private DeactivateOAuthClient() {
    }

    public static Operation<DeactivateCommand, OAuthClientDeactivated> of(OAuthClientRepository repo) {
        return Operation.<DeactivateCommand, OAuthClientDeactivated>named("DeactivateOAuthClient")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "id is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    OAuthClient c = Access.byId(repo, cmd.id()).deactivate();
                    return Plan.save(c, repo, OAuthClientDeactivated.of(ec, c));
                });
    }
}

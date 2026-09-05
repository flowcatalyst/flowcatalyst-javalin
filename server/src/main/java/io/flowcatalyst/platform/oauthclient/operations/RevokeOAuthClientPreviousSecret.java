package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientPreviousSecretRevoked;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Ends a rotation overlap immediately (A-22, `docs/improvements.md`) and
/// emits [OAuthClientPreviousSecretRevoked]. **Idempotent**: revoking when
/// no overlap is in flight succeeds and changes nothing, so an operator
/// hitting it twice — or after the timer already fired — never sees an
/// error. Platform-level config (`Authorize.publicAccess()`); the controller
/// gates anchor-only.
public final class RevokeOAuthClientPreviousSecret {

    private RevokeOAuthClientPreviousSecret() {
    }

    public static Operation<RevokeOAuthClientPreviousSecretCommand, OAuthClientPreviousSecretRevoked> of(OAuthClientRepository repo) {
        return Operation.<RevokeOAuthClientPreviousSecretCommand, OAuthClientPreviousSecretRevoked>named("RevokeOAuthClientPreviousSecret")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "id is required");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    OAuthClient c = Access.byId(repo, cmd.id());
                    OAuthClient.RevokeResult result = c.revokePreviousSecret();
                    return Plan.save(result.client(), repo,
                            OAuthClientPreviousSecretRevoked.of(ec, result.client(), result.dropped()));
                });
    }
}

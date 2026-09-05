package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Applies the supplied fields (spec §6.3, [OAuthClient#update]) and emits
/// [OAuthClientUpdated]. Platform-level config (`Authorize.publicAccess()`);
/// the controller gates anchor-only.
public final class UpdateOAuthClient {

    private UpdateOAuthClient() {
    }

    public static Operation<UpdateOAuthClientCommand, OAuthClientUpdated> of(OAuthClientRepository repo) {
        return Operation.<UpdateOAuthClientCommand, OAuthClientUpdated>named("UpdateOAuthClient")
                .validate(cmd -> {
                    if (cmd.id() == null || cmd.id().isBlank()) {
                        throw UseCaseException.validation("ID_REQUIRED", "id is required");
                    }
                    if (cmd.clientName() != null && cmd.clientName().isBlank()) {
                        throw UseCaseException.validation("CLIENT_NAME_REQUIRED", "clientName cannot be empty");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.byId (there is none — platform-level)
                .execute((cmd, ec) -> {
                    OAuthClient c = Access.byId(repo, cmd.id());
                    OAuthClient updated = c.update(new OAuthClient.Changes(
                            cmd.clientName(), cmd.redirectUris(), cmd.postLogoutRedirectUris(), cmd.grantTypes(),
                            cmd.defaultScopes(), cmd.allowedOrigins(), cmd.applicationIds(), cmd.pkceRequired(),
                            cmd.portalClientId(), cmd.apiAccess()));
                    return Plan.save(updated, repo, OAuthClientUpdated.of(ec, updated));
                });
    }
}

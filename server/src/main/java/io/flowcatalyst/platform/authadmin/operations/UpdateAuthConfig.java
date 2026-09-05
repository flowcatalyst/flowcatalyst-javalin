package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.AuthProvider;
import io.flowcatalyst.platform.authadmin.ClientAuthConfig;
import io.flowcatalyst.platform.authadmin.ClientAuthConfigRepository;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents.AuthConfigUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces an auth config's client grants, provider and OIDC settings
/// (spec §4.2, one [ClientAuthConfig#update] transition) and emits
/// [AuthConfigUpdated]. No OIDC-completeness check here — kept as Go
/// (spec §8 D3, an owner question).
public final class UpdateAuthConfig {

    private UpdateAuthConfig() {
    }

    public static Operation<UpdateAuthConfigCommand, AuthConfigUpdated> of(ClientAuthConfigRepository repo) {
        return Operation.<UpdateAuthConfigCommand, AuthConfigUpdated>named("UpdateAuthConfig")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.authProvider() != null) {
                        AuthProvider.parseStrict(cmd.authProvider());
                    }
                })
                // Auth configs are anchor-only with no per-resource dimension; the handler's requireAnchor is the whole check.
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    ClientAuthConfig c = Access.authConfigById(repo, cmd.id()).update(changesOf(cmd));
                    return Plan.save(c, repo, AuthConfigUpdated.of(ec, c));
                });
    }

    /// The command's fields as the aggregate's [ClientAuthConfig.Changes] (`null` = absent).
    private static ClientAuthConfig.Changes changesOf(UpdateAuthConfigCommand cmd) {
        return new ClientAuthConfig.Changes(cmd.primaryClientId(), cmd.additionalClientIds(), cmd.grantedClientIds(),
                cmd.authProvider() == null ? null : AuthProvider.parseStrict(cmd.authProvider()),
                cmd.oidcIssuerUrl(), cmd.oidcClientId(), cmd.oidcMultiTenant(), cmd.oidcIssuerPattern(), cmd.oidcClientSecretRef());
    }
}

package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientCreated;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/// Creates an OAuth client (unique by `clientId`), mints a secret for
/// `CONFIDENTIAL` clients, and emits [OAuthClientCreated]. Platform-level
/// config — the client_id is the OAuth2 identifier, not a tenant, so there
/// is no per-resource scope dimension and the operation is intentionally
/// open (`Authorize.publicAccess()`); the controller gates writes anchor-only
/// (spec §6.3).
///
/// The one resource-level rule (security-fixes S1.4): a `principalId` is the
/// principal every `client_credentials` token this client mints acts as, so
/// it must name an existing, **active `SERVICE`** principal the caller can
/// reach — otherwise the client would be a credential for any user (an
/// anchor administrator included) its creator names. Missing, not a service
/// principal, inactive and out of reach all answer the same 400, so the
/// route is no oracle over the principal table.
public final class CreateOAuthClient {

    private CreateOAuthClient() {
    }

    /// @param principals resolves `principalId` for the service-principal rule
    /// @param disclose   receives the plaintext secret for a `CONFIDENTIAL`
    ///                   client, once, before commit — the handler reads it
    ///                   back only after `run` succeeds
    public static Operation<CreateOAuthClientCommand, OAuthClientCreated> of(OAuthClientRepository repo, PrincipalRepository principals,
                                                                            Optional<Encryption> encryption, Consumer<String> disclose) {
        Objects.requireNonNull(principals, "principals");
        Objects.requireNonNull(disclose, "disclose");
        return Operation.<CreateOAuthClientCommand, OAuthClientCreated>named("CreateOAuthClient")
                .validate(cmd -> {
                    if (cmd.clientName() == null || cmd.clientName().isBlank()) {
                        throw UseCaseException.validation("CLIENT_NAME_REQUIRED", "clientName is required");
                    }
                    try {
                        ClientType.parse(cmd.clientType());
                    } catch (ClientType.UnrecognisedClientTypeException e) {
                        throw UseCaseException.validation("INVALID_CLIENT_TYPE", "clientType must be PUBLIC or CONFIDENTIAL");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // anchor-only gate is enforced at the handler (spec §6.3)
                .execute((cmd, ec) -> {
                    String clientId = cmd.clientId() == null || cmd.clientId().isBlank()
                            ? EntityType.OAUTH_CLIENT.generate() : cmd.clientId().trim();
                    if (repo.findByClientId(clientId).isPresent()) {
                        throw UseCaseException.conflict("CLIENT_ID_EXISTS", "OAuth client_id '" + clientId + "' already exists");
                    }
                    ClientType type = ClientType.parse(cmd.clientType()); // already validated
                    if (cmd.principalId() != null && !cmd.principalId().isBlank()) {
                        requireReachableServicePrincipal(principals, cmd.principalId());
                    }

                    OAuthClient c = OAuthClient.create(clientId, cmd.clientName(), type)
                            .withRedirectUris(cmd.redirectUris())
                            .withPostLogoutRedirectUris(cmd.postLogoutRedirectUris())
                            .withGrantTypes(cmd.grantTypes())
                            .withDefaultScopes(cmd.defaultScopes())
                            .withAllowedOrigins(cmd.allowedOrigins())
                            .withApplicationIds(cmd.applicationIds())
                            .withPrincipalId(cmd.principalId())
                            .withPkceRequired(cmd.pkceRequired() == null || cmd.pkceRequired())
                            .withPortalAppId(cmd.portalAppId())
                            .withPortalAndApiAccess(cmd.portalClientId(), cmd.apiAccess() != null && cmd.apiAccess());

                    if (type == ClientType.CONFIDENTIAL) {
                        String plaintext = Secrets.generatePlaintext();
                        c = c.withSecretRef(Secrets.hashedRef(encryption, plaintext));
                        disclose.accept(plaintext);
                    }

                    return Plan.save(c, repo, OAuthClientCreated.of(ec, c));
                });
    }

    /// @throws UseCaseException validation `INVALID_SERVICE_PRINCIPAL` unless `principalId`
    ///                          is an existing, active `SERVICE` principal the caller can reach
    private static void requireReachableServicePrincipal(PrincipalRepository principals, String principalId) {
        AuthContext ac = Auth.current();
        boolean ok = principals.findById(principalId)
                .filter(Principal::isService)
                .filter(Principal::active)
                .filter(p -> Checks.canAccessScope(ac, p.clientId()))
                .isPresent();
        if (!ok) {
            throw UseCaseException.validation("INVALID_SERVICE_PRINCIPAL",
                    "principalId must name an active service principal you can reach");
        }
    }
}

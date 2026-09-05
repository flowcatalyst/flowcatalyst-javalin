package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientCreated;
import io.flowcatalyst.platform.oauthclient.operations.Secrets;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountCode;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.WebhookCredentials;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountCreated;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Creates a service account together with its linked `SERVICE` principal, a
/// `CONFIDENTIAL` OAuth client that can mint `client_credentials` tokens for
/// it, and its initial webhook credentials, atomically (spec §4.1, §8). A
/// multi-aggregate orchestration — hence [TxOperation] rather than
/// [io.flowcatalyst.sdk.usecase.op.Operation] — because it writes three
/// aggregates and returns a custom result, not a single domain event. Mirrors
/// `application.operations.ProvisionServiceAccount`'s OAuth-client shape
/// (same grant types, same scope, same secret helper) and Go's
/// `create_credentials.go` commit order: service account, then principal,
/// then client.
///
/// **Deliberate deviation from `create_credentials.go`:** Go never scopes
/// the minted client's `applicationIds` — only the linked principal is
/// confined. This port scopes both, matching `ProvisionServiceAccount`'s
/// template and the principal's own confinement a few lines above: an
/// app-scoped account's `client_credentials` token should not carry a wider
/// application claim than the account it was minted for.
///
/// When `applicationId` is given, the linked principal is confined to that
/// application (`allApplications = false` + one application-access grant) —
/// mirrors Go's `CreateServiceAccountWithCredentials`, which confines the
/// same way so the token's `applications` claim carries only that app.
public final class CreateServiceAccountWithCredentials {

    private CreateServiceAccountWithCredentials() {
    }

    /// @param serviceAccount      the created row
    /// @param principalId         the linked `SERVICE` principal's id
    /// @param oauthClientId       the OAuth client's internal row id (`oac_…`)
    /// @param oauthClientClientId the OAuth2 `client_id` string
    /// @param oauthClientSecret   the minted client secret, plaintext, returned once
    /// @param authToken           the minted webhook bearer token, plaintext, returned once
    /// @param signingSecret       the minted webhook HMAC signing secret, plaintext, returned once
    public record Result(ServiceAccount serviceAccount, String principalId, String oauthClientId,
                         String oauthClientClientId, String oauthClientSecret, String authToken, String signingSecret) {
        public Result {
            Objects.requireNonNull(serviceAccount, "serviceAccount");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(oauthClientId, "oauthClientId");
            Objects.requireNonNull(oauthClientClientId, "oauthClientClientId");
            Objects.requireNonNull(oauthClientSecret, "oauthClientSecret");
            Objects.requireNonNull(authToken, "authToken");
            Objects.requireNonNull(signingSecret, "signingSecret");
        }
    }

    public static TxOperation<CreateCommand, Result> of(ServiceAccountRepository saRepo, PrincipalRepository principals,
            OAuthClientRepository oauthClients, Optional<Encryption> encryption) {
        return TxOperation.<CreateCommand, Result>named("CreateServiceAccountWithCredentials")
                .validate(cmd -> {
                    ServiceAccountCode.parse(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                })
                // Admin-managed create, no per-client resource check (spec §4.1); the coarse
                // "may write service accounts" permission is enforced at the handler.
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    ServiceAccountCode code = ServiceAccountCode.parse(cmd.code());
                    if (saRepo.findByCode(code.value()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Service account with code '" + code.value() + "' already exists");
                    }

                    String authToken = WebhookSecrets.generateAuthToken();
                    String signingSecret = WebhookSecrets.generateSigningSecret();
                    ServiceAccount sa = ServiceAccount.create(code, cmd.name())
                            .withDescription(cmd.description())
                            .withScope(cmd.scope())
                            .withApplicationId(cmd.applicationId())
                            .withClientIds(cmd.clientIds())
                            .withWebhookCredentials(WebhookCredentials.bearer(authToken, signingSecret));

                    // 1. Service account: through the sink, so the event + audit are atomic with the row.
                    scoped.commit(sa, saRepo, ServiceAccountCreated.of(ec, sa), cmd);

                    // 2. Linked SERVICE principal — a persistence detail of SA creation (no event of
                    // its own, exactly like Go: "the principal row is a persistence detail of SA
                    // creation"). Confine it to the application when one was given.
                    Principal principal = Principal.newService(sa.id(), sa.name());
                    boolean appScoped = cmd.applicationId() != null && !cmd.applicationId().isBlank();
                    if (appScoped) {
                        principal = principal.assignApplicationAccess(List.of(cmd.applicationId()), false).principal();
                    }
                    try {
                        if (appScoped) {
                            principals.withApplicationAccess().persist(principal, scoped.dbTx());
                        } else {
                            principals.persist(principal, scoped.dbTx());
                        }
                    } catch (SQLException e) {
                        throw UseCaseException.internal("PERSIST", "service principal persist failed", e);
                    }

                    // 3. A CONFIDENTIAL OAuth client scoped to this account's SERVICE principal
                    // (spec §8; Go create_credentials.go's oc, same shape as
                    // ProvisionServiceAccount's). No app key configured -> Secrets.encryptedRef
                    // throws internal SECRET here, rolling back the service account and principal
                    // writes above too (spec §4.1's atomicity).
                    String plaintext = Secrets.generatePlaintext();
                    String secretRef = Secrets.encryptedRef(encryption, plaintext);
                    OAuthClient oc = OAuthClient.create(EntityType.OAUTH_CLIENT.generate(), sa.name() + " Client", ClientType.CONFIDENTIAL)
                            .withSecretRef(secretRef)
                            .withPrincipalId(principal.id())
                            .withGrantTypes(List.of("client_credentials", "refresh_token"))
                            .withDefaultScopes(List.of("openid"))
                            .withApplicationIds(appScoped ? List.of(cmd.applicationId()) : List.of());
                    scoped.commit(oc, oauthClients, OAuthClientCreated.of(ec, oc), cmd);

                    return new Result(sa, principal.id(), oc.id(), oc.clientId(), plaintext, authToken, signingSecret);
                });
    }
}

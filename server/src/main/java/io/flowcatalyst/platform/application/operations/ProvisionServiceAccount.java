package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationServiceAccountProvisioned;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientCreated;
import io.flowcatalyst.platform.oauthclient.operations.Secrets;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.RoleAssignment;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountCode;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.WebhookCredentials;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountCreated;
import io.flowcatalyst.platform.serviceaccount.operations.WebhookSecrets;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Provisions a dedicated service account for an application (spec
/// `application.md` §10): its own service account, the `SERVICE` principal
/// that carries its authority, the application's write-once
/// `serviceAccountId`, and a `CONFIDENTIAL` OAuth client that can mint
/// `client_credentials` tokens for it — four aggregates, one transaction.
/// A multi-aggregate orchestration — hence [TxOperation] — mirroring the
/// shape of `serviceaccount.operations.CreateServiceAccountWithCredentials`,
/// extended with the application attach and the OAuth client.
///
/// Applications are platform-level (spec §5): `Authorize.publicAccess()`,
/// with the anchor-only gate enforced at the handler.
public final class ProvisionServiceAccount {

    /// The seeded, least-privilege role every provisioned service account's
    /// principal is granted (spec §10; seed: `PlatformRoles`/`Permissions`).
    private static final String APPLICATION_SERVICE_ROLE = "platform:application-service";

    /// The assignment-source tag for this role grant — distinct from
    /// [RoleAssignment#ADMIN_ASSIGNED]: nobody assigned it by hand, the
    /// provisioning flow did (spec §11 q8, Go parity).
    private static final String PROVISIONED = "PROVISIONED";

    private ProvisionServiceAccount() {
    }

    /// @param principalId       the linked `SERVICE` principal's id — what the application's `serviceAccountId` stores
    /// @param serviceAccountName the service account's display name (`<application name> Service Account`)
    /// @param oauthClientId     the OAuth client's internal row id (`oac_…`)
    /// @param oauthClientClientId the OAuth2 `client_id` string
    /// @param oauthClientSecret the minted client secret, plaintext, returned once
    public record Result(String principalId, String serviceAccountName, String oauthClientId,
                         String oauthClientClientId, String oauthClientSecret) {
        public Result {
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(serviceAccountName, "serviceAccountName");
            Objects.requireNonNull(oauthClientId, "oauthClientId");
            Objects.requireNonNull(oauthClientClientId, "oauthClientClientId");
            Objects.requireNonNull(oauthClientSecret, "oauthClientSecret");
        }
    }

    public static TxOperation<ProvisionServiceAccountCommand, Result> of(
            ApplicationRepository applications,
            ServiceAccountRepository serviceAccounts,
            PrincipalRepository principals,
            OAuthClientRepository oauthClients,
            Optional<Encryption> encryption) {
        return TxOperation.<ProvisionServiceAccountCommand, Result>named("ProvisionServiceAccount")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.applicationId(), "APPLICATION_ID_REQUIRED", "Application ID is required"))
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    Application app = Access.byId(applications, cmd.applicationId());
                    if (app.hasServiceAccount()) {
                        throw UseCaseException.conflict("ALREADY_PROVISIONED",
                                "Application already has a service account provisioned");
                    }

                    // 1. Service account: code app:<code>, generated webhook credentials — deliberately
                    // bypasses ServiceAccountCode.parse's format check (Go's serviceaccount.New does too):
                    // the "app:" prefix carries a colon the create-op's CODE_REQUIRED/INVALID_CODE_FORMAT
                    // pattern would reject, and this path is not reached through that operation's Validate.
                    ServiceAccountCode saCode = new ServiceAccountCode("app:" + app.code());
                    String saName = app.name() + " Service Account";
                    String authToken = WebhookSecrets.generateAuthToken();
                    String signingSecret = WebhookSecrets.generateSigningSecret();
                    ServiceAccount sa = ServiceAccount.create(saCode, saName)
                            .withDescription("Service account for application: " + app.name())
                            .withApplicationId(app.id())
                            .withWebhookCredentials(WebhookCredentials.bearer(authToken, signingSecret));
                    scoped.commit(sa, serviceAccounts, ServiceAccountCreated.of(ec, sa), cmd);

                    // 2. Its SERVICE principal — a persistence detail of SA creation (no event of its
                    // own, matching CreateServiceAccountWithCredentials), confined to this application
                    // and granted the seeded application-service role so its token reflects real scope
                    // instead of leaning entirely on the anchor-tier bypass (spec §10).
                    Principal principal = Principal.newService(sa.id(), sa.name())
                            .assignApplicationAccess(List.of(app.id()), false).principal()
                            .withRoles(List.of(new RoleAssignment(APPLICATION_SERVICE_ROLE, PROVISIONED, Instant.now())));
                    try {
                        principals.withApplicationAccess().persist(principal, scoped.dbTx());
                        principals.withRoles().persist(principal, scoped.dbTx());
                    } catch (SQLException e) {
                        throw UseCaseException.internal("PERSIST", "service principal persist failed", e);
                    }

                    // 3. The application: serviceAccountId = the principal id (spec §1.1 FK), re-stamped.
                    Application attached = app.attachServiceAccount(principal.id());
                    scoped.commit(attached, applications,
                            ApplicationServiceAccountProvisioned.of(ec, attached, sa.id(), saCode.value()), cmd);

                    // 4. A CONFIDENTIAL OAuth client scoped to this application's service principal.
                    // No app key configured -> Secrets.encryptedRef throws internal SECRET here,
                    // rolling back every write above (spec §10).
                    String plaintext = Secrets.generatePlaintext();
                    String secretRef = Secrets.encryptedRef(encryption, plaintext);
                    OAuthClient oc = OAuthClient.create(EntityType.OAUTH_CLIENT.generate(), app.name() + " Service Account Client", ClientType.CONFIDENTIAL)
                            .withSecretRef(secretRef)
                            .withPrincipalId(principal.id())
                            .withGrantTypes(List.of("client_credentials", "refresh_token"))
                            .withDefaultScopes(List.of("openid"))
                            .withApplicationIds(List.of(app.id()));
                    scoped.commit(oc, oauthClients, OAuthClientCreated.of(ec, oc), cmd);

                    return new Result(principal.id(), sa.name(), oc.id(), oc.clientId(), plaintext);
                });
    }
}

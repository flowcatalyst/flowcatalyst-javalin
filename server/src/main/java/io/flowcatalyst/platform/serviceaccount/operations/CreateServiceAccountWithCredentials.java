package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountCode;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.WebhookCredentials;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/// Creates a service account together with its linked `SERVICE` principal
/// and mints its initial webhook credentials, atomically (spec §4.1). A
/// multi-aggregate orchestration — hence [TxOperation] rather than
/// [io.flowcatalyst.sdk.usecase.op.Operation] — because it writes two
/// aggregates and returns a custom result, not a single domain event.
///
/// **Gap versus the spec, not improvised around:** spec §4.1 also says
/// "Creation also mints an OAuth client secret (`generateOAuthClientSecret`
/// returns plaintext + a stored reference)". That cannot be implemented here:
/// there is no Java `auth`/`OAuthClient` aggregate in this codebase yet (the
/// auth port is blocked on owner rulings — see `docs/spec/auth-core.md`,
/// `docs/spec/auth-identity.md`, and the project memory's "no auth Java
/// until then"). This operation deliberately stops at what `serviceaccount`
/// and the already-ported `principal` aggregate own: the service account row,
/// its linked principal, and the webhook bearer + signing secret. It does
/// **not** mint or return anything OAuth-shaped — see
/// `io.flowcatalyst.platform.serviceaccount.api.ServiceAccountApi#create`
/// for how the wire response (which the lockfile requires to carry an
/// `oauth` object) copes with that absence.
///
/// When `applicationId` is given, the linked principal is confined to that
/// application (`allApplications = false` + one application-access grant) —
/// mirrors Go's `CreateServiceAccountWithCredentials`, which confines the
/// same way so the token's `applications` claim carries only that app.
public final class CreateServiceAccountWithCredentials {

    private CreateServiceAccountWithCredentials() {
    }

    /// @param serviceAccount the created row
    /// @param principalId    the linked `SERVICE` principal's id
    /// @param authToken      the minted bearer token, plaintext, returned once
    /// @param signingSecret  the minted HMAC signing secret, plaintext, returned once
    public record Result(ServiceAccount serviceAccount, String principalId, String authToken, String signingSecret) {
        public Result {
            Objects.requireNonNull(serviceAccount, "serviceAccount");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(authToken, "authToken");
            Objects.requireNonNull(signingSecret, "signingSecret");
        }
    }

    public static TxOperation<CreateCommand, Result> of(ServiceAccountRepository saRepo, PrincipalRepository principals) {
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

                    return new Result(sa, principal.id(), authToken, signingSecret);
                });
    }
}

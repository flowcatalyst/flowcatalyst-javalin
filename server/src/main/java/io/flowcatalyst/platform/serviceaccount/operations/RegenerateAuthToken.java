package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountTokenRegenerated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.Objects;
import java.util.function.Consumer;

/// Rotates a service account's bearer token and emits
/// [ServiceAccountTokenRegenerated] (spec §4.6). The plaintext is disclosed
/// exactly once through `disclose` — a **caller-owned sink**: it is minted
/// after the not-found check (there is no further authorisation rule here,
/// only the anchor-only gate the handler already enforced) and the handler
/// reads it only after `run` returns successfully, so a rolled-back commit
/// discloses nothing and the plaintext cannot outlive the request. See
/// `principal.operations.SetDeveloperCredential` and spec §5 — this is the
/// shape that replaced Go's process-wide two-minute stash.
public final class RegenerateAuthToken {

    private RegenerateAuthToken() {
    }

    public static Operation<RegenerateAuthTokenCommand, ServiceAccountTokenRegenerated> of(ServiceAccountRepository repo, Consumer<String> disclose) {
        Objects.requireNonNull(disclose, "disclose");
        return Operation.<RegenerateAuthTokenCommand, ServiceAccountTokenRegenerated>named("RegenerateAuthToken")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.serviceAccountId(), "SERVICE_ACCOUNT_ID_REQUIRED", "Service account ID is required"))
                .authorize(Operation.Authorize.publicAccess()) // anchor-only gate is enforced at the handler (spec §3)
                .execute((cmd, ec) -> {
                    ServiceAccount sa = Access.byId(repo, cmd.serviceAccountId());
                    String token = WebhookSecrets.generateAuthToken();
                    disclose.accept(token);
                    sa = sa.withToken(token);
                    return Plan.save(sa, repo, ServiceAccountTokenRegenerated.of(ec, sa));
                });
    }
}

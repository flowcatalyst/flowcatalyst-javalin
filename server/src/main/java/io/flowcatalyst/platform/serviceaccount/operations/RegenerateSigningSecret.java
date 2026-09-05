package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountSecretRegenerated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.Objects;
import java.util.function.Consumer;

/// Rotates a service account's HMAC signing secret and emits
/// [ServiceAccountSecretRegenerated] (spec §4.6). See [RegenerateAuthToken]
/// for the caller-owned disclosure sink this shares.
public final class RegenerateSigningSecret {

    private RegenerateSigningSecret() {
    }

    public static Operation<RegenerateSigningSecretCommand, ServiceAccountSecretRegenerated> of(ServiceAccountRepository repo, Consumer<String> disclose) {
        Objects.requireNonNull(disclose, "disclose");
        return Operation.<RegenerateSigningSecretCommand, ServiceAccountSecretRegenerated>named("RegenerateSigningSecret")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.serviceAccountId(), "SERVICE_ACCOUNT_ID_REQUIRED", "Service account ID is required"))
                .authorize(Operation.Authorize.publicAccess()) // anchor-only gate is enforced at the handler (spec §3)
                .execute((cmd, ec) -> {
                    ServiceAccount sa = Access.byId(repo, cmd.serviceAccountId());
                    String secret = WebhookSecrets.generateSigningSecret();
                    disclose.accept(secret);
                    sa = sa.withSigningSecret(secret);
                    return Plan.save(sa, repo, ServiceAccountSecretRegenerated.of(ec, sa));
                });
    }
}

package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationDisabledForClient;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Disables the existing [ClientConfig] for the pair (a never-enabled pair
/// is a 404 — spec §6) and emits [ApplicationDisabledForClient], also when
/// it was already disabled (spec §2).
public final class DisableApplicationForClient {

    private DisableApplicationForClient() {
    }

    public static Operation<DisableForClientCommand, ApplicationDisabledForClient> of(ClientConfigRepository configs) {
        return Operation.<DisableForClientCommand, ApplicationDisabledForClient>named("DisableApplicationForClient")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationId(), "APPLICATION_ID_REQUIRED", "Application ID is required");
                    UseCaseException.requireNonBlank(cmd.clientId(), "CLIENT_ID_REQUIRED", "Client ID is required");
                })
                // The resource is the client: a non-anchor needs access to that client (spec §5).
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    ClientConfig cfg = configs.findByApplicationAndClient(cmd.applicationId(), cmd.clientId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("ClientConfig", cmd.applicationId() + ":" + cmd.clientId()))
                            .disable();
                    return Plan.save(cfg, configs, ApplicationDisabledForClient.of(ec, cfg));
                });
    }
}

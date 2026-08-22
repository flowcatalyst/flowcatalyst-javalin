package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationEnabledForClient;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Ensures an enabled [ClientConfig] exists for the (application, client)
/// pair — re-enabling an existing row or creating a fresh one (spec §6) —
/// and emits [ApplicationEnabledForClient].
public final class EnableApplicationForClient {

    private EnableApplicationForClient() {
    }

    public static Operation<EnableForClientCommand, ApplicationEnabledForClient> of(ApplicationRepository apps,
                                                                                    ClientConfigRepository configs) {
        return Operation.<EnableForClientCommand, ApplicationEnabledForClient>named("EnableApplicationForClient")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationId(), "APPLICATION_ID_REQUIRED", "Application ID is required");
                    UseCaseException.requireNonBlank(cmd.clientId(), "CLIENT_ID_REQUIRED", "Client ID is required");
                })
                // The resource is the client: a non-anchor needs access to that client (spec §5).
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    Access.byId(apps, cmd.applicationId());
                    if (!apps.clientExists(cmd.clientId())) {
                        throw UseCaseException.resourceNotFound("Client", cmd.clientId());
                    }
                    ClientConfig cfg = configs.findByApplicationAndClient(cmd.applicationId(), cmd.clientId())
                            .map(ClientConfig::enable)
                            .orElseGet(() -> ClientConfig.create(cmd.applicationId(), cmd.clientId()));
                    return Plan.save(cfg, configs, ApplicationEnabledForClient.of(ec, cfg));
                });
    }
}

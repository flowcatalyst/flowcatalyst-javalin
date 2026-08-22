package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.platform.application.ClientConfigRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ClientApplicationsUpdated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Replaces a client's enabled-application set in one transaction (spec §7):
/// desired-but-not-enabled pairs are enabled (re-using a disabled row or
/// creating one), enabled-but-not-desired pairs are disabled, everything
/// else is untouched. One [ClientApplicationsUpdated] rollup describes the
/// diff; an empty diff still emits it so the request is on record.
///
/// Owned by this aggregate but reached from the client surface
/// (`PUT /api/clients/{id}/applications`), whose handler holds the coarse gate.
public final class UpdateClientApplications {

    private UpdateClientApplications() {
    }

    public static Operation<UpdateClientApplicationsCommand, ClientApplicationsUpdated> of(ApplicationRepository apps,
                                                                                           ClientConfigRepository configs) {
        return Operation.<UpdateClientApplicationsCommand, ClientApplicationsUpdated>named("UpdateClientApplications")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.clientId(), "CLIENT_ID_REQUIRED", "Client ID is required"))
                // The resource is the client: a non-anchor needs access to that client (spec §5).
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    if (!apps.clientExists(cmd.clientId())) {
                        throw UseCaseException.resourceNotFound("Client", cmd.clientId());
                    }
                    // Every requested application must exist before any row is touched.
                    for (String appId : cmd.enabledApplicationIds()) {
                        UseCaseException.requireNonBlank(appId, "APPLICATION_ID_REQUIRED", "Application ID must be non-empty");
                        Access.byId(apps, appId);
                    }

                    Map<String, ClientConfig> currentByApp = configs.findByClient(cmd.clientId()).stream()
                            .collect(Collectors.toMap(ClientConfig::applicationId, Function.identity(), (a, _) -> a, LinkedHashMap::new));
                    Set<String> desired = Set.copyOf(cmd.enabledApplicationIds());

                    var toPersist = new ArrayList<ClientConfig>();
                    var enabledAdded = new ArrayList<String>();
                    var disabledRemoved = new ArrayList<String>();
                    for (String appId : cmd.enabledApplicationIds()) {
                        ClientConfig existing = currentByApp.get(appId);
                        if (existing != null && existing.enabled()) continue;
                        if (enabledAdded.contains(appId)) continue; // duplicate in the input
                        toPersist.add(existing != null ? existing.enable() : ClientConfig.create(appId, cmd.clientId()));
                        enabledAdded.add(appId);
                    }
                    for (ClientConfig existing : currentByApp.values()) {
                        if (existing.enabled() && !desired.contains(existing.applicationId())) {
                            toPersist.add(existing.disable());
                            disabledRemoved.add(existing.applicationId());
                        }
                    }

                    var rollup = ClientApplicationsUpdated.of(ec, cmd.clientId(), cmd.enabledApplicationIds(), enabledAdded, disabledRemoved);
                    return toPersist.isEmpty() ? Plan.emit(rollup) : Plan.saveAll(List.copyOf(toPersist), configs, rollup);
                });
    }
}

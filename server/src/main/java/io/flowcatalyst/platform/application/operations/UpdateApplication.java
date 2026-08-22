package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces the supplied mutable fields (absent = unchanged) and emits
/// [ApplicationUpdated]. Code and type are immutable.
public final class UpdateApplication {

    private UpdateApplication() {
    }

    public static Operation<UpdateCommand, ApplicationUpdated> of(ApplicationRepository repo) {
        return Operation.<UpdateCommand, ApplicationUpdated>named("UpdateApplication")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // platform-level; load-or-404 is in Access.byId
                .execute((cmd, ec) -> {
                    Application a = Access.byId(repo, cmd.id());
                    if (cmd.name() != null) a = a.withName(cmd.name());
                    if (cmd.description() != null) a = a.withDescription(cmd.description());
                    if (cmd.iconUrl() != null) a = a.withIconUrl(cmd.iconUrl());
                    if (cmd.website() != null) a = a.withWebsite(cmd.website());
                    if (cmd.logo() != null) a = a.withLogo(cmd.logo());
                    if (cmd.logoMimeType() != null) a = a.withLogoMimeType(cmd.logoMimeType());
                    if (cmd.defaultBaseUrl() != null) a = a.withDefaultBaseUrl(cmd.defaultBaseUrl());
                    return Plan.save(a, repo, ApplicationUpdated.of(ec, a));
                });
    }
}

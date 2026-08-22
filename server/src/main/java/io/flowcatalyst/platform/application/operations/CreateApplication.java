package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationCode;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates an application (unique by normalised code) and emits [ApplicationCreated].
public final class CreateApplication {

    private CreateApplication() {
    }

    public static Operation<CreateCommand, ApplicationCreated> of(ApplicationRepository repo) {
        return Operation.<CreateCommand, ApplicationCreated>named("CreateApplication")
                .validate(cmd -> {
                    ApplicationCode.parse(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                })
                // Platform-level resource: nothing per-instance to check (spec §5).
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    String code = ApplicationCode.parse(cmd.code()).value();
                    if (repo.findByCode(code).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS", "Application with code '" + code + "' already exists");
                    }
                    Application a = Application.create(ApplicationType.parse(cmd.type()), code, cmd.name())
                            .withDescription(cmd.description())
                            .withIconUrl(cmd.iconUrl())
                            .withWebsite(cmd.website())
                            .withLogo(cmd.logo())
                            .withLogoMimeType(cmd.logoMimeType())
                            .withDefaultBaseUrl(cmd.defaultBaseUrl());
                    return Plan.save(a, repo, ApplicationCreated.of(ec, a));
                });
    }
}

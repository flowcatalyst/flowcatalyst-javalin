package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationActivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// [Application#activate] (idempotent, spec §2) and emits [ApplicationActivated].
public final class ActivateApplication {

    private ActivateApplication() {
    }

    public static Operation<ActivateCommand, ApplicationActivated> of(ApplicationRepository repo) {
        return Operation.<ActivateCommand, ApplicationActivated>named("ActivateApplication")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // platform-level; load-or-404 is in Access.byId
                .execute((cmd, ec) -> {
                    Application a = Access.byId(repo, cmd.id()).activate();
                    return Plan.save(a, repo, ApplicationActivated.of(ec, a));
                });
    }
}

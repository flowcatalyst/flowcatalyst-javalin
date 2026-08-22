package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.operations.ApplicationEvents.ApplicationDeactivated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// [Application#deactivate] (idempotent, spec §2) and emits [ApplicationDeactivated].
public final class DeactivateApplication {

    private DeactivateApplication() {
    }

    public static Operation<DeactivateCommand, ApplicationDeactivated> of(ApplicationRepository repo) {
        return Operation.<DeactivateCommand, ApplicationDeactivated>named("DeactivateApplication")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // platform-level; load-or-404 is in Access.byId
                .execute((cmd, ec) -> {
                    Application a = Access.byId(repo, cmd.id()).deactivate();
                    return Plan.save(a, repo, ApplicationDeactivated.of(ec, a));
                });
    }
}

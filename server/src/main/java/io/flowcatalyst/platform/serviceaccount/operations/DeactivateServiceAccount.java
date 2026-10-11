package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountDeactivated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Marks a service account inactive and emits [ServiceAccountDeactivated] (spec §4.2).
public final class DeactivateServiceAccount {

    private DeactivateServiceAccount() {
    }

    public static Operation<DeactivateCommand, ServiceAccountDeactivated> of(ServiceAccountRepository repo) {
        return Operation.<DeactivateCommand, ServiceAccountDeactivated>named("DeactivateServiceAccount")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(cmd -> Access.requireWrite(repo, Auth.current(), cmd.id())) // application confinement (F4); the permission and anchor reach are the handler's
                .execute((cmd, ec) -> {
                    ServiceAccount sa = Access.byId(repo, cmd.id()).deactivate();
                    return Plan.save(sa, repo, ServiceAccountDeactivated.of(ec, sa));
                });
    }
}

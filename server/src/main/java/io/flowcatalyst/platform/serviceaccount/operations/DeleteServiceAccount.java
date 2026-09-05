package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a service account and emits [ServiceAccountDeleted] (spec §4.2).
/// No cascade into the linked principal — matches Go.
public final class DeleteServiceAccount {

    private DeleteServiceAccount() {
    }

    public static Operation<DeleteCommand, ServiceAccountDeleted> of(ServiceAccountRepository repo) {
        return Operation.<DeleteCommand, ServiceAccountDeleted>named("DeleteServiceAccount")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // admin-managed, no per-client resource check (spec §4.2)
                .execute((cmd, ec) -> {
                    ServiceAccount sa = Access.byId(repo, cmd.id());
                    return Plan.delete(sa, repo, ServiceAccountDeleted.of(ec, sa));
                });
    }
}

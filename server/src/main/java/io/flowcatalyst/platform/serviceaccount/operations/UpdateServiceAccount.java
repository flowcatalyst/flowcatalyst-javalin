package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Replaces the mutable fields of an existing service account and emits
/// [ServiceAccountUpdated] (spec §4.2). `code` is immutable.
public final class UpdateServiceAccount {

    private UpdateServiceAccount() {
    }

    public static Operation<UpdateCommand, ServiceAccountUpdated> of(ServiceAccountRepository repo) {
        return Operation.<UpdateCommand, ServiceAccountUpdated>named("UpdateServiceAccount")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null && cmd.name().isBlank()) {
                        throw UseCaseException.validation("NAME_REQUIRED", "name cannot be empty");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // admin-managed update, no per-client resource check (spec §4.2)
                .execute((cmd, ec) -> {
                    ServiceAccount sa = Access.byId(repo, cmd.id())
                            .update(new ServiceAccount.Changes(cmd.name(), cmd.description(), cmd.scope(), cmd.clientIds(), cmd.webhookCredentials()));
                    return Plan.save(sa, repo, ServiceAccountUpdated.of(ec, sa));
                });
    }
}

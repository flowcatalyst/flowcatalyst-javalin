package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionDeleted;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hard-deletes a subscription with its bindings and config and emits
/// [SubscriptionDeleted].
public final class DeleteSubscription {

    private DeleteSubscription() {
    }

    public static Operation<DeleteCommand, SubscriptionDeleted> of(SubscriptionRepository repo) {
        return Operation.<DeleteCommand, SubscriptionDeleted>named("DeleteSubscription")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Subscription s = Access.loadScoped(repo, cmd.id());
                    return Plan.delete(s, repo, SubscriptionDeleted.of(ec, s));
                });
    }
}

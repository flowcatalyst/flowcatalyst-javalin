package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionPaused;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `PAUSED` ([Subscription#pause], idempotent) and emits [SubscriptionPaused].
public final class PauseSubscription {

    private PauseSubscription() {
    }

    public static Operation<PauseCommand, SubscriptionPaused> of(SubscriptionRepository repo) {
        return Operation.<PauseCommand, SubscriptionPaused>named("PauseSubscription")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Subscription s = Access.loadScoped(repo, cmd.id()).pause();
                    return Plan.save(s, repo, SubscriptionPaused.of(ec, s));
                });
    }
}

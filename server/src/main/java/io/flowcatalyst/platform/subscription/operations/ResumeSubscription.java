package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionResumed;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// → `ACTIVE` ([Subscription#resume], idempotent) and emits [SubscriptionResumed].
public final class ResumeSubscription {

    private ResumeSubscription() {
    }

    public static Operation<ResumeCommand, SubscriptionResumed> of(SubscriptionRepository repo) {
        return Operation.<ResumeCommand, SubscriptionResumed>named("ResumeSubscription")
                .validate(cmd -> UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required"))
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Subscription s = Access.loadScoped(repo, cmd.id()).resume();
                    return Plan.save(s, repo, SubscriptionResumed.of(ec, s));
                });
    }
}

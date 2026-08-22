package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.DispatchMode;
import io.flowcatalyst.platform.subscription.EndpointUrl;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Applies the given fields to an existing subscription (absent ones are
/// kept; lists are replaced wholesale) and emits [SubscriptionUpdated]. The
/// code, client, source and status are immutable here.
public final class UpdateSubscription {

    private UpdateSubscription() {
    }

    public static Operation<UpdateCommand, SubscriptionUpdated> of(SubscriptionRepository repo) {
        return Operation.<UpdateCommand, SubscriptionUpdated>named("UpdateSubscription")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                    if (cmd.endpoint() != null) EndpointUrl.parse(cmd.endpoint());
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Subscription s = Access.loadScoped(repo, cmd.id());
                    if (cmd.name() != null) s = s.withName(cmd.name().strip());
                    if (cmd.description() != null) s = s.withDescription(cmd.description());
                    if (cmd.endpoint() != null) s = s.withEndpoint(EndpointUrl.parse(cmd.endpoint()).value());
                    if (cmd.connectionId() != null) s = s.withConnectionId(cmd.connectionId());
                    if (cmd.eventTypes() != null) s = s.withEventTypes(cmd.eventTypes());
                    if (cmd.customConfig() != null) s = s.withCustomConfig(cmd.customConfig());
                    if (cmd.mode() != null) s = s.withMode(DispatchMode.parse(cmd.mode()));
                    if (cmd.timeoutSeconds() != null) s = s.withTimeoutSeconds(cmd.timeoutSeconds());
                    if (cmd.maxRetries() != null) s = s.withMaxRetries(cmd.maxRetries());
                    if (cmd.delaySeconds() != null) s = s.withDelaySeconds(cmd.delaySeconds());
                    if (cmd.maxAgeSeconds() != null) s = s.withMaxAgeSeconds(cmd.maxAgeSeconds());
                    if (cmd.dispatchPoolId() != null) s = s.withDispatchPoolId(cmd.dispatchPoolId());
                    if (cmd.serviceAccountId() != null) s = s.withServiceAccountId(cmd.serviceAccountId());
                    if (cmd.dataOnly() != null) s = s.withDataOnly(cmd.dataOnly());
                    return Plan.save(s, repo, SubscriptionUpdated.of(ec, s));
                });
    }
}

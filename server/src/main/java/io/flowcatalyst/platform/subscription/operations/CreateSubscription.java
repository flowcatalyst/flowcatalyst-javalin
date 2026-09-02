package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.subscription.EndpointUrl;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionCode;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a `UI`-sourced subscription (unique by normalised code within the
/// client scope) with the given bindings and settings, and emits
/// [SubscriptionCreated].
public final class CreateSubscription {

    private CreateSubscription() {
    }

    public static Operation<CreateCommand, SubscriptionCreated> of(SubscriptionRepository repo) {
        return Operation.<CreateCommand, SubscriptionCreated>named("CreateSubscription")
                .validate(cmd -> {
                    SubscriptionCode.parse(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                    EndpointUrl.parse(cmd.endpoint());
                    if (cmd.eventTypes().isEmpty()) {
                        throw UseCaseException.validation("EVENT_TYPES_REQUIRED", "at least one event type binding is required");
                    }
                })
                // The target client is a command field, so the per-resource check
                // can run before execute: a client-bound create needs access to that
                // client; a platform-wide (null clientId) create needs anchor.
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    String code = SubscriptionCode.parse(cmd.code()).value();
                    if (repo.findByCodeAndClient(code, cmd.clientId()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Subscription with code '" + code + "' already exists");
                    }
                    Subscription s = Subscription.create(code, cmd.name().strip(), EndpointUrl.parse(cmd.endpoint()).value())
                            .withDescription(cmd.description())
                            .withClientId(cmd.clientId())
                            .withConnectionId(cmd.connectionId())
                            .withDispatchPoolId(cmd.dispatchPoolId())
                            .withServiceAccountId(cmd.serviceAccountId())
                            .withEventTypes(cmd.eventTypes())
                            .withCustomConfig(cmd.customConfig())
                            .withCreatedBy(ec.principalId());
                    if (cmd.mode() != null) s = s.withMode(DispatchMode.parse(cmd.mode()));
                    if (cmd.timeoutSeconds() != null) s = s.withTimeoutSeconds(cmd.timeoutSeconds());
                    if (cmd.maxRetries() != null) s = s.withMaxRetries(cmd.maxRetries());
                    if (cmd.delaySeconds() != null) s = s.withDelaySeconds(cmd.delaySeconds());
                    if (cmd.maxAgeSeconds() != null) s = s.withMaxAgeSeconds(cmd.maxAgeSeconds());
                    if (cmd.dataOnly() != null) s = s.withDataOnly(cmd.dataOnly());
                    return Plan.save(s, repo, SubscriptionCreated.of(ec, s));
                });
    }
}

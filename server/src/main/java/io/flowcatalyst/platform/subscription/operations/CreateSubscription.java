package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.serviceaccount.SigningReach;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.subscription.EndpointUrl;
import io.flowcatalyst.platform.shared.dispatch.QueuePriority;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionCode;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionCreated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates a `UI`-sourced subscription (unique by normalised code within the
/// client scope) with the given bindings and settings, and emits
/// [SubscriptionCreated]. A named service account or connection must exist
/// and be one the caller may sign with ([Access#requireUsableSigners]).
public final class CreateSubscription {

    private CreateSubscription() {
    }

    public static Operation<CreateCommand, SubscriptionCreated> of(SubscriptionRepository repo,
                                                                   ConnectionRepository connections, SigningReach reach) {
        return Operation.<CreateCommand, SubscriptionCreated>named("CreateSubscription")
                .validate(cmd -> {
                    SubscriptionCode.parse(cmd.code());
                    UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name is required");
                    EndpointUrl.parse(cmd.endpoint());
                    QueuePriority.parse(cmd.queue()); // null-safe for absent/blank; throws INVALID_QUEUE otherwise (R1a)
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
                    // Admin/API create never stamps applicationCode (null = shared);
                    // the uniqueness key is (applicationCode, clientId, code) (spec
                    // code-first-connections.md §2).
                    if (repo.findByCode(code, null, cmd.clientId()).isPresent()) {
                        throw UseCaseException.conflict("CODE_EXISTS",
                                "Subscription with code '" + code + "' already exists");
                    }
                    // Admin create never has an owning application (applicationCode is null).
                    Access.requireUsableSigners(reach, connections, null,
                            Access.blankToNull(cmd.serviceAccountId()), true, Access.blankToNull(cmd.connectionId()), true);
                    QueuePriority queue = QueuePriority.parse(cmd.queue());
                    Subscription s = Subscription.create(code, cmd.name().strip(), EndpointUrl.parse(cmd.endpoint()).value())
                            .withDescription(cmd.description())
                            .withClientId(cmd.clientId())
                            .withConnectionId(cmd.connectionId())
                            .withDispatchPoolId(cmd.dispatchPoolId())
                            .withServiceAccountId(cmd.serviceAccountId())
                            .withEventTypes(cmd.eventTypes())
                            .withCustomConfig(cmd.customConfig())
                            .withQueue(queue == null ? null : queue.name())
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

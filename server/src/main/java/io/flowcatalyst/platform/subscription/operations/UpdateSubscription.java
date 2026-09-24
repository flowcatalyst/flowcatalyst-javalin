package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.serviceaccount.SigningReach;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.subscription.EndpointUrl;
import io.flowcatalyst.platform.shared.dispatch.QueuePriority;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.Objects;

/// Applies the given fields to an existing subscription (absent ones are
/// kept; lists are replaced wholesale) and emits [SubscriptionUpdated]. The
/// code, client, source and status are immutable here.
///
/// Whenever the update changes where deliveries go or who signs them — the
/// endpoint, the service account or the connection — the resulting account
/// and connection must be ones the caller may sign with
/// ([Access#requireUsableSigners]): re-pointing the endpoint of a
/// subscription signed by an account the caller cannot reach would hand
/// that account's credentials to the caller's endpoint. Re-sending the
/// current values (the SPA sends the whole form) changes nothing and is
/// not re-checked.
public final class UpdateSubscription {

    private UpdateSubscription() {
    }

    public static Operation<UpdateCommand, SubscriptionUpdated> of(SubscriptionRepository repo,
                                                                   ConnectionRepository connections, SigningReach reach) {
        return Operation.<UpdateCommand, SubscriptionUpdated>named("UpdateSubscription")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    if (cmd.name() != null) {
                        UseCaseException.requireNonBlank(cmd.name(), "NAME_REQUIRED", "name cannot be empty");
                    }
                    if (cmd.endpoint() != null) EndpointUrl.parse(cmd.endpoint());
                    if (cmd.queue() != null) QueuePriority.parse(cmd.queue()); // throws INVALID_QUEUE for non-blank junk (R1a)
                })
                .authorize(Operation.Authorize.publicAccess()) // per-resource check is in Access.loadScoped
                .execute((cmd, ec) -> {
                    Subscription before = Access.loadScoped(repo, cmd.id());
                    Subscription s = before;
                    if (cmd.name() != null) s = s.withName(cmd.name().strip());
                    if (cmd.description() != null) s = s.withDescription(cmd.description());
                    if (cmd.endpoint() != null) s = s.withEndpoint(EndpointUrl.parse(cmd.endpoint()).value());
                    if (cmd.connectionId() != null) s = s.withConnectionId(cmd.connectionId());
                    if (cmd.eventTypes() != null) s = s.withEventTypes(cmd.eventTypes());
                    if (cmd.customConfig() != null) s = s.withCustomConfig(cmd.customConfig());
                    if (cmd.mode() != null) s = s.withMode(DispatchMode.parse(cmd.mode()));
                    if (cmd.queue() != null) {
                        QueuePriority q = QueuePriority.parse(cmd.queue());
                        s = s.withQueue(q == null ? null : q.name());
                    }
                    if (cmd.timeoutSeconds() != null) s = s.withTimeoutSeconds(cmd.timeoutSeconds());
                    if (cmd.maxRetries() != null) s = s.withMaxRetries(cmd.maxRetries());
                    if (cmd.delaySeconds() != null) s = s.withDelaySeconds(cmd.delaySeconds());
                    if (cmd.maxAgeSeconds() != null) s = s.withMaxAgeSeconds(cmd.maxAgeSeconds());
                    if (cmd.dispatchPoolId() != null) s = s.withDispatchPoolId(cmd.dispatchPoolId());
                    if (cmd.serviceAccountId() != null) s = s.withServiceAccountId(cmd.serviceAccountId());
                    if (cmd.dataOnly() != null) s = s.withDataOnly(cmd.dataOnly());
                    String accountBefore = Access.blankToNull(before.serviceAccountId());
                    String accountAfter = Access.blankToNull(s.serviceAccountId());
                    String connectionBefore = Access.blankToNull(before.connectionId());
                    String connectionAfter = Access.blankToNull(s.connectionId());
                    boolean accountChanged = !Objects.equals(accountBefore, accountAfter);
                    boolean connectionChanged = !Objects.equals(connectionBefore, connectionAfter);
                    boolean endpointChanged = !Objects.equals(before.endpoint(), s.endpoint());
                    if (accountChanged || connectionChanged || endpointChanged) {
                        Access.requireUsableSigners(reach, connections, s.applicationCode(),
                                accountAfter, accountChanged, connectionAfter, connectionChanged);
                    }
                    return Plan.save(s, repo, SubscriptionUpdated.of(ec, s));
                });
    }
}

package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.subscription.operations.SyncEventTypeBindingInput;
import io.flowcatalyst.platform.subscription.operations.SyncSubscriptionInput;
import io.flowcatalyst.platform.subscription.operations.SyncSubscriptionsCommand;

import java.util.List;

/// `SyncSubscriptionsRequest` (lockfile, `code-first-connections.md` §3):
/// `{clientId, subscriptions[]: {code, name, description, target,
/// connectionId, connectionCode, sharedConnection, eventTypes[]:
/// {eventTypeCode, filter}, dispatchPoolCode, mode, maxRetries,
/// timeoutSeconds, dataOnly}}`. `clientId` is the client's id OR its
/// identifier slug — [SdkSyncApi] resolves it to an id before this DTO
/// builds its command.
public record SyncSubscriptionsRequest(String clientId, List<Input> subscriptions) {

    public record Binding(String eventTypeCode, String filter) {
        SyncEventTypeBindingInput toInput() {
            return new SyncEventTypeBindingInput(eventTypeCode, filter);
        }
    }

    public record Input(String code, String name, String description, String target, String connectionId,
                        String connectionCode, Boolean sharedConnection,
                        List<Binding> eventTypes, String dispatchPoolCode, String mode, Integer maxRetries,
                        Integer timeoutSeconds, Boolean dataOnly) {
        SyncSubscriptionInput toInput() {
            return new SyncSubscriptionInput(code, name, description, target, connectionId,
                    connectionCode, Boolean.TRUE.equals(sharedConnection),
                    eventTypes == null ? List.of() : eventTypes.stream().map(Binding::toInput).toList(),
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, Boolean.TRUE.equals(dataOnly));
        }
    }

    SyncSubscriptionsCommand toCommand(String applicationId, String applicationCode, String resolvedClientId, boolean removeUnlisted) {
        return new SyncSubscriptionsCommand(applicationId, applicationCode, resolvedClientId,
                subscriptions == null ? List.of() : subscriptions.stream().map(Input::toInput).toList(), removeUnlisted);
    }
}

package io.flowcatalyst.platform.sdksync.api;

import io.flowcatalyst.platform.subscription.operations.SyncEventTypeBindingInput;
import io.flowcatalyst.platform.subscription.operations.SyncSubscriptionInput;
import io.flowcatalyst.platform.subscription.operations.SyncSubscriptionsCommand;

import java.util.List;

/// `SyncSubscriptionsRequest` (lockfile): `{subscriptions[]: {code, name,
/// description, target, connectionId, eventTypes[]: {eventTypeCode, filter},
/// dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly}}`.
public record SyncSubscriptionsRequest(List<Input> subscriptions) {

    public record Binding(String eventTypeCode, String filter) {
        SyncEventTypeBindingInput toInput() {
            return new SyncEventTypeBindingInput(eventTypeCode, filter);
        }
    }

    public record Input(String code, String name, String description, String target, String connectionId,
                        List<Binding> eventTypes, String dispatchPoolCode, String mode, Integer maxRetries,
                        Integer timeoutSeconds, Boolean dataOnly) {
        SyncSubscriptionInput toInput() {
            return new SyncSubscriptionInput(code, name, description, target, connectionId,
                    eventTypes == null ? List.of() : eventTypes.stream().map(Binding::toInput).toList(),
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, Boolean.TRUE.equals(dataOnly));
        }
    }

    SyncSubscriptionsCommand toCommand(String applicationId, String applicationCode, boolean removeUnlisted) {
        return new SyncSubscriptionsCommand(applicationId, applicationCode,
                subscriptions == null ? List.of() : subscriptions.stream().map(Input::toInput).toList(), removeUnlisted);
    }
}

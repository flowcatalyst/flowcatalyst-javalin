package io.flowcatalyst.platform.subscription.operations;

import java.util.List;

/// One subscription definition in a [SyncSubscriptionsCommand] batch (spec §7).
///
/// @param code             stored as given — no trim, no lowercase, no pattern check (spec open question 3)
/// @param name             required, as given
/// @param description      optional
/// @param target           delivery endpoint; non-blank, format not checked (spec open question 3)
/// @param connectionId     optional; must exist when given (`CONNECTION_NOT_FOUND`); absent **clears** an existing link
/// @param eventTypes       at least one binding; replaces the stored bindings
/// @param dispatchPoolCode optional; resolved among platform-wide pools, silently ignored when unknown (spec open question 6)
/// @param mode             accepted for wire compatibility and **ignored** (spec §7)
/// @param maxRetries       optional; replaced only when present
/// @param timeoutSeconds   optional; replaced only when present
/// @param dataOnly         plain boolean — absent on the wire means `false` (spec open question 4)
public record SyncSubscriptionInput(
        String code,
        String name,
        String description,
        String target,
        String connectionId,
        List<SyncEventTypeBindingInput> eventTypes,
        String dispatchPoolCode,
        String mode,
        Integer maxRetries,
        Integer timeoutSeconds,
        boolean dataOnly) {

    public SyncSubscriptionInput {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }
}

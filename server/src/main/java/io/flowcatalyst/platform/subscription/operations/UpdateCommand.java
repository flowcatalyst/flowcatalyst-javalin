package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.ConfigEntry;
import io.flowcatalyst.platform.subscription.EventTypeBinding;

import java.util.List;

/// The input DTO for [UpdateSubscription] (audit `operation` = `UpdateCommand`).
/// Every field but `id` is optional: `null` leaves the current value
/// unchanged, so nothing can be cleared through an update (spec §3, open
/// question 5). A non-null `eventTypes` / `customConfig` — an empty list
/// included — replaces the stored list wholesale.
///
/// `queue` follows the same "absent = unchanged" rule as every other field
/// here: a `null` command field leaves the stored priority untouched. A
/// present-but-blank string is not absence — it parses to `null` (ruling
/// R1) and is applied, clearing the priority.
public record UpdateCommand(
        String id,
        String name,
        String description,
        String endpoint,
        String connectionId,
        List<EventTypeBinding> eventTypes,
        List<ConfigEntry> customConfig,
        String mode,
        String queue,
        Integer timeoutSeconds,
        Integer maxRetries,
        Integer delaySeconds,
        Integer maxAgeSeconds,
        String dispatchPoolId,
        String serviceAccountId,
        Boolean dataOnly) {

    public UpdateCommand {
        eventTypes = eventTypes == null ? null : List.copyOf(eventTypes);
        customConfig = customConfig == null ? null : List.copyOf(customConfig);
    }
}

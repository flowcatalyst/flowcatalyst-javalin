package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.ConfigEntry;
import io.flowcatalyst.platform.subscription.EventTypeBinding;

import java.util.List;

/// The input DTO for [CreateSubscription]. The record's simple name is the
/// audit log's `operation` column, so it must stay `CreateCommand`.
///
/// Absent optionals take the aggregate's defaults (spec §1): `mode`
/// (`null` ⇒ `NEXT_ON_ERROR`, read leniently — ledger `X-01`), `timeoutSeconds`,
/// `maxRetries`, `delaySeconds`, `maxAgeSeconds`, `dataOnly`.
///
/// @param code             raw code; normalised (trimmed, lower-cased) by the operation
/// @param name             human-readable name; trimmed
/// @param endpoint         `http(s)://…` delivery URL
/// @param description      optional
/// @param clientId         optional client scope; `null` means platform-wide
/// @param connectionId     optional delivery connection (not checked to exist — spec open question 13)
/// @param dispatchPoolId   optional pool id (not checked to exist — spec open question 6)
/// @param serviceAccountId optional, not validated
/// @param eventTypes       at least one binding
/// @param customConfig     optional key/values; `null` = none
/// @param mode             optional dispatch mode string
/// @param timeoutSeconds   optional
/// @param maxRetries       optional
/// @param delaySeconds     optional
/// @param maxAgeSeconds    optional
/// @param dataOnly         optional
public record CreateCommand(
        String code,
        String name,
        String endpoint,
        String description,
        String clientId,
        String connectionId,
        String dispatchPoolId,
        String serviceAccountId,
        List<EventTypeBinding> eventTypes,
        List<ConfigEntry> customConfig,
        String mode,
        Integer timeoutSeconds,
        Integer maxRetries,
        Integer delaySeconds,
        Integer maxAgeSeconds,
        Boolean dataOnly) {

    public CreateCommand {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        customConfig = customConfig == null ? List.of() : List.copyOf(customConfig);
    }
}

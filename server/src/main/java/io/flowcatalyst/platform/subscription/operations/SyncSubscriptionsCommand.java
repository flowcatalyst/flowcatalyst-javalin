package io.flowcatalyst.platform.subscription.operations;

import java.util.List;

/// The input DTO for [SyncSubscriptions] (audit `operation` = `SyncSubscriptionsCommand`).
/// `applicationId` is what the use case authorizes against; `applicationCode`
/// scopes the reconciliation (rows stamped with it) and is carried for event
/// provenance. `removeUnlisted` hard-deletes the `API`/`CODE`-sourced rows of
/// the application that are not in `subscriptions`; `UI` rows are never
/// touched by sync (spec §7).
public record SyncSubscriptionsCommand(String applicationId, String applicationCode,
                                       List<SyncSubscriptionInput> subscriptions, boolean removeUnlisted) {

    public SyncSubscriptionsCommand {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }
}

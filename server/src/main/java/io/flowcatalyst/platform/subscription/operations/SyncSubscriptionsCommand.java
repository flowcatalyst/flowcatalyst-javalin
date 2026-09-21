package io.flowcatalyst.platform.subscription.operations;

import java.util.List;

/// The input DTO for [SyncSubscriptions] (audit `operation` = `SyncSubscriptionsCommand`).
/// `applicationId` is what the use case authorizes against; `applicationCode`
/// scopes the reconciliation (rows stamped with it) and is carried for event
/// provenance. `clientId` is already resolved to an id by the handler — the
/// wire accepts either the client's id or its identifier slug
/// (`code-first-connections.md` §3) — `null` scopes the sync to the
/// application's global, client-less subscriptions (today's behaviour,
/// unchanged for callers that never send a client). `removeUnlisted`
/// hard-deletes the `API`/`CODE`-sourced rows of `(applicationCode, clientId)`
/// that are not in `subscriptions`; `UI` rows are never touched by sync
/// (spec §7).
public record SyncSubscriptionsCommand(String applicationId, String applicationCode, String clientId,
                                       List<SyncSubscriptionInput> subscriptions, boolean removeUnlisted) {

    public SyncSubscriptionsCommand {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
    }
}

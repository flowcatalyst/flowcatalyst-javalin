package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.EventTypeBinding;

/// One event-type binding in a [SyncSubscriptionInput]: the pattern and an
/// optional filter (carried, never persisted — spec open question 2).
public record SyncEventTypeBindingInput(String eventTypeCode, String filter) {

    /// The stored binding: no id, no spec version; an absent pattern is
    /// stored as `""` (the column is `NOT NULL`, the pattern is not
    /// validated — spec §4, open question 10).
    EventTypeBinding toBinding() {
        return new EventTypeBinding(null, eventTypeCode == null ? "" : eventTypeCode, null, filter);
    }
}

package io.flowcatalyst.outbox;

import java.time.Instant;
import java.util.Objects;

/// One claimed `outbox_messages` row (spec §2, §3) — exactly what
/// [OutboxProcessor] and [HttpDispatcher] need to dispatch it and decide its
/// outcome: the wire payload verbatim, its grouping key (`null` = ungrouped),
/// and the retry count previous attempts left behind. `status` is not
/// carried — every row [OutboxRepository#claimPending] returns was `PENDING`
/// the instant it was claimed.
public record OutboxItem(String id, OutboxItemType type, String messageGroup, String payload, int retryCount,
                          Instant createdAt) {

    public OutboxItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// Whether this item belongs to a message group (spec §4) — an empty
    /// string is treated the same as `NULL` (never a distinct "grouped into
    /// the empty group").
    public boolean grouped() {
        return messageGroup != null && !messageGroup.isEmpty();
    }
}

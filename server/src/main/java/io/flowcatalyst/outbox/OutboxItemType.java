package io.flowcatalyst.outbox;

/// The three outbox item kinds (`outbox_messages.type`, spec §2) and the
/// platform ingest batch route each one posts to (spec §6). The constant
/// names match [io.flowcatalyst.sdk.outbox.OutboxMessage.MessageType]'s wire
/// names exactly — the SDK writes the `type` column with `Enum#name()`, and
/// this is the reader on the processor side of the same table.
public enum OutboxItemType {
    EVENT("/api/events/batch"),
    DISPATCH_JOB("/api/dispatch-jobs/batch"),
    AUDIT_LOG("/api/audit-logs/batch");

    private final String apiPath;

    OutboxItemType(String apiPath) {
        this.apiPath = apiPath;
    }

    /// The platform ingest batch route this item type is POSTed to (spec §6).
    public String apiPath() {
        return apiPath;
    }
}

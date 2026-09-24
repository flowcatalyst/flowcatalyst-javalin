package io.flowcatalyst.platform.audit.operations;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// Emitted once per run of the dashboard's **temporary** row-redaction
/// action (`docs/spec/audit-redaction.md` "Temporary: redact existing rows
/// from the dashboard"), alongside the [RedactExistingAuditLogs] audit row
/// [io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork#emitEvent] writes for it.
public record AuditLogsRedacted(EventMetadata metadata, int scanned, int redacted) implements DomainEvent {

    private static final String SOURCE = "platform:admin";
    private static final String TYPE = "platform:admin:audit-log:redacted";
    private static final String SUBJECT = "platform.audit-logs";
    private static final String MESSAGE_GROUP = "platform:audit-logs";

    public static AuditLogsRedacted of(ExecutionContext ec, int scanned, int redacted) {
        return new AuditLogsRedacted(
                EventMetadata.of(ec, TYPE, SOURCE, SUBJECT).withMessageGroup(MESSAGE_GROUP),
                scanned, redacted);
    }

    @Override
    public Object data() {
        return new Data(scanned, redacted);
    }

    private record Data(int scanned, int redacted) {
    }
}

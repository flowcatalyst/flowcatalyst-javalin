package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;

import java.sql.SQLException;

/// The destination for domain events and audit logs. The unit of work does
/// not know whether events go to `outbox_messages` (consumer apps, via
/// `OutboxSink`) or directly to `msg_events` / `aud_logs` (the platform, via
/// its own sink); it calls this interface inside the transaction it has open.
///
/// Implementations write using the supplied [DbTx] and must not commit or
/// roll back. Any exception rolls the whole use case back and surfaces as an
/// internal `EVENT_WRITE` / `AUDIT_WRITE` error.
public interface Sink {

    /// Append the domain event to its destination.
    void writeEvent(DbTx tx, DomainEvent event) throws SQLException;

    /// Append an audit log row for (event, command) — the command is the
    /// original input DTO, recorded as the audit subject. Implementations may
    /// no-op when audit logging is disabled.
    void writeAudit(DbTx tx, DomainEvent event, Object command) throws SQLException;
}

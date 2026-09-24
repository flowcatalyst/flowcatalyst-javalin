package io.flowcatalyst.platform.audit.operations;

/// The "command" audited for the dashboard's **temporary** "redact existing
/// audit rows" action (`docs/spec/audit-redaction.md` "Temporary: redact
/// existing rows from the dashboard"; to be removed once the sweep is no
/// longer needed). Unlike most commands this one carries the RESULT of the
/// sweep, not its input (the action takes no input) — the spec is explicit
/// that the audit row's `operation_json` is `{scanned, redacted}`, and
/// `operation_json` is always the audited command's own JSON
/// (`SinkSupport#redactedCommandJson`), so those fields have to live here.
/// The record's simple name is the audit `operation` column the spec names
/// verbatim — `RedactExistingAuditLogs`.
public record RedactExistingAuditLogs(int scanned, int redacted) {
}

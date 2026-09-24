package io.flowcatalyst.sdk.usecase;

import java.util.Set;

/// An optional command capability (`docs/spec/audit-redaction.md`, "The rule
/// (one definition, three languages)"): a command declares extra top-level
/// field names [AuditRedaction] must mask even though the name rule alone
/// would keep them — e.g. `SetPropertyCommand.value`, which is only secret
/// when the command's own `valueType` says so.
///
/// A command that does not implement this interface has no declared masked
/// fields; the name rule alone still applies to it.
public interface AuditMasked {

    /// Top-level field names (as they appear on the wire / in the JSON
    /// document) to mask unconditionally, on top of the name rule.
    Set<String> auditMaskedFields();
}

package io.flowcatalyst.platform.audit;

import io.flowcatalyst.platform.platformconfig.operations.SetPropertyCommand;
import io.flowcatalyst.sdk.usecase.AuditRedaction;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/// The one redaction rule (`docs/spec/audit-redaction.md`) applied to an
/// already-stored `aud_logs` row: the name rule, plus — for a
/// `SetPropertyCommand` row — `value` unless the row's own `valueType` is
/// exactly `PLAIN`. A stored row has only JSON, never a live command, so
/// [SetPropertyCommand#maskedFieldsFor] is asked with the row's own
/// `valueType`.
///
/// Used by the existing-rows sweep and by every read of the audit-log API:
/// a row written before source-side redaction landed (or by an application
/// SDK that predates it) must not be served as stored.
public final class StoredAuditRedaction {

    /// The one operation name whose stored JSON needs a masked field the
    /// name rule alone would keep (`value`, spec "The rule").
    public static final String SET_PROPERTY_OPERATION = "SetPropertyCommand";

    private StoredAuditRedaction() {
    }

    public static JsonNode redact(String operation, JsonNode operationJson) {
        if (operationJson == null) return null;
        Set<String> masked = SET_PROPERTY_OPERATION.equals(operation)
                ? SetPropertyCommand.maskedFieldsFor(valueTypeOf(operationJson))
                : Set.of();
        return AuditRedaction.redact(operationJson, masked);
    }

    private static String valueTypeOf(JsonNode operationJson) {
        JsonNode valueType = operationJson.get("valueType");
        return valueType == null || valueType.isNull() ? null : valueType.asString();
    }
}

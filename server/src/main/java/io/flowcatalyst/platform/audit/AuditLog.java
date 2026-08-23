package io.flowcatalyst.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// One audit log entry (spec §1): the platform's memory of *who asked for
/// what*. Rows are written only by the unit-of-work sink; this aggregate is
/// read-only, so it has no transitions — just the row shape with the
/// principal's name hydrated on read.
///
/// `operationJson` is the command document as stored (`null` when the column
/// is `NULL` or holds the JSON literal `null`); `principalName` is derived
/// from `iam_principals` and `null` when unknown; `applicationId` /
/// `clientId` are `null` until another writer fills those columns.
public record AuditLog(
        String id,
        String entityType,
        String entityId,
        String operation,
        JsonNode operationJson,
        String principalId,
        String principalName,
        String applicationId,
        String clientId,
        Instant performedAt) implements HasId {

    public AuditLog {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(performedAt, "performedAt");
        if (operationJson != null && operationJson.isNull()) operationJson = null;
    }

    /// The keyset position of this entry in the newest-first order (spec §4).
    public AuditLogCursor cursor() {
        return new AuditLogCursor(performedAt, id);
    }
}

package io.flowcatalyst.platform.ingest;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.audit.AuditLog;
import io.flowcatalyst.platform.shared.tsid.EntityType;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/// Applies the ingest defaults for one audit-log item (sdk-ingest spec
/// §4.3) in the domain. `applicationId` / `clientId` arrive already
/// resolved (code lookup + tenant guard are the API handler's job — an
/// unresolvable code is `SKIPPED` before this class is ever called, spec
/// §4.3).
public final class AuditLogIngestMapper {

    private AuditLogIngestMapper() {
    }

    /// @param principalId    the item's `principalId` — required (owner ruling 2026-09-06 #10b: an
    ///                        item without one is refused by the handler, never defaulted to the caller)
    /// @param performedAtRaw the item's own `performedAt` (RFC 3339 text), or `null` if absent
    public static AuditLog toLog(String entityType, String entityId, String operation, JsonNode operationData,
                                  String principalId, String performedAtRaw, String applicationId, String clientId) {
        if (blank(principalId) == null) throw new IllegalArgumentException("principalId is required");
        String actingPrincipal = principalId;
        // entityType/entityId/operation are unvalidated on the wire (Go performs no presence
        // check); the aggregate's invariant forbids null, matching the NOT NULL columns, so an
        // absent value reads as "" rather than throwing (Go's zero-value behaviour).
        return new AuditLog(EntityType.AUDIT_LOG.generate(), nullToEmpty(entityType), nullToEmpty(entityId),
                nullToEmpty(operation), operationData, actingPrincipal, null, applicationId, clientId,
                parsePerformedAt(performedAtRaw));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /// RFC 3339; absent or any parse failure silently falls back to now
    /// (sdk-ingest spec §4.3, §5 D5 — kept as Go, not rejected).
    private static Instant parsePerformedAt(String raw) {
        if (blank(raw) == null) return Instant.now();
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException _) {
            return Instant.now();
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

package io.flowcatalyst.sdk.usecase.jdbc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.flowcatalyst.sdk.usecase.AuditMasked;
import io.flowcatalyst.sdk.usecase.AuditRedaction;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Helpers shared by [Sink] implementations so the platform sink and the
/// outbox sink agree on the details that end up in rows.
public final class SinkSupport {

    private SinkSupport() {}

    /// `null` for `null`/empty strings so optional VARCHAR columns store SQL
    /// `NULL` rather than empty-string sentinels.
    public static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /// Empty string for `null` — for payload fields the other SDKs always emit.
    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /// The audit `operation` name: the command's simple class name
    /// (`CreateCommand`), or `Unknown` when there is no command or the class
    /// is anonymous. Command records therefore carry the wire-visible
    /// operation name — name them exactly as the Go commands are named.
    public static String commandName(Object command) {
        if (command == null) return "Unknown";
        String name = command.getClass().getSimpleName();
        return name.isEmpty() ? "Unknown" : name;
    }

    /// The event time, defaulting to now when the event has none.
    public static Instant eventTime(DomainEvent event) {
        Instant t = event.time();
        return t == null ? Instant.now() : t;
    }

    /// The `context_data` array every sink stores next to an event:
    /// `[{key: principalId, value}, {key: aggregateType, value}]`, in that
    /// order — the shape the Go and TypeScript sinks write.
    ///
    /// Reads the actor from `event.metadata()` directly rather than calling
    /// `event.principalId()`: a `DomainEvent` implementation is free to
    /// declare its own record component (naming the event's *subject*, not
    /// its actor) that would otherwise shadow `DomainEvent#principalId()`'s
    /// inherited default. Going through the metadata keeps this correct
    /// regardless of what any given event record declares.
    public static List<Map<String, String>> contextData(DomainEvent event) {
        return List.of(
                contextEntry("principalId", orEmpty(event.metadata().principalId())),
                contextEntry("aggregateType", EventConventions.extractAggregateType(event.subject())));
    }

    /// The command document to write to `operation_json` (`docs/spec/audit-redaction.md`):
    /// serialised, then redacted by the one shared rule — the name rule
    /// plus, when `command` implements [AuditMasked], its declared top-level
    /// fields. Shared by [io.flowcatalyst.sdk.usecase.outbox.OutboxSink] and
    /// the platform's own sink so both agree on what an audit row may store.
    public static JsonNode redactedCommandJson(ObjectMapper mapper, Object command) {
        JsonNode tree = mapper.valueToTree(command);
        Set<String> masked = command instanceof AuditMasked am ? am.auditMaskedFields() : Set.of();
        return AuditRedaction.redact(tree, masked);
    }

    private static Map<String, String> contextEntry(String key, String value) {
        var entry = new LinkedHashMap<String, String>(2);
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }
}

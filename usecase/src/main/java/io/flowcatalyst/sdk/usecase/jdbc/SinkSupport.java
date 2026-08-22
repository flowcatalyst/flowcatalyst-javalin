package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public static List<Map<String, String>> contextData(DomainEvent event) {
        return List.of(
                contextEntry("principalId", orEmpty(event.principalId())),
                contextEntry("aggregateType", EventConventions.extractAggregateType(event.subject())));
    }

    private static Map<String, String> contextEntry(String key, String value) {
        var entry = new LinkedHashMap<String, String>(2);
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }
}

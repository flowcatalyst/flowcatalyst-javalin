package io.flowcatalyst.sdk.usecase;

/// Naming conventions shared by every SDK for event types, subjects and
/// message groups. Kept byte-for-byte compatible with the Go and TypeScript
/// helpers: these strings are stored in `msg_events` and matched by
/// subscriptions, so they are part of the wire contract.
public final class EventConventions {

    private EventConventions() {}

    /// `app:domain:aggregate:action` — e.g. `platform:admin:eventtype:created`.
    public static String buildEventType(String app, String domain, String aggregate, String action) {
        return app + ":" + domain + ":" + aggregate + ":" + action;
    }

    /// `domain.aggregate.id` — e.g. `platform.eventtype.evt_01H…`.
    public static String buildSubject(String domain, String aggregate, String id) {
        return domain + "." + aggregate + "." + id;
    }

    /// `domain:aggregate:id` — the per-aggregate FIFO ordering key.
    public static String buildMessageGroup(String domain, String aggregate, String id) {
        return domain + ":" + aggregate + ":" + id;
    }

    /// `platform.eventtype.123` → `Eventtype`. Used by sinks to fill the
    /// `aggregate_type` / `entity_type` columns. Fewer than two segments →
    /// `Unknown`; an empty second segment stays empty.
    public static String extractAggregateType(String subject) {
        String[] parts = subject.split("\\.", -1);
        if (parts.length < 2) return "Unknown";
        String s = parts[1];
        if (s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /// `platform.eventtype.123` → `123`. Fewer than three segments → empty string.
    public static String extractEntityId(String subject) {
        String[] parts = subject.split("\\.", -1);
        return parts.length < 3 ? "" : parts[2];
    }
}

package io.flowcatalyst.platform.subscription;

import java.util.Objects;

/// One event-type pattern a subscription listens to (a
/// `msg_subscription_event_types` row). The pattern is colon-separated; a
/// `*` segment matches exactly one segment of the event's code (spec §1,
/// "Matching").
///
/// @param eventTypeId   optional event-type id, stored verbatim, never resolved (spec §1)
/// @param eventTypeCode the pattern, required; not validated (spec §4)
/// @param specVersion   optional schema version, stored verbatim
/// @param filter        optional filter expression — carried in memory, **not persisted** (spec open question 2); reads back `null`
public record EventTypeBinding(String eventTypeId, String eventTypeCode, String specVersion, String filter) {

    public EventTypeBinding {
        Objects.requireNonNull(eventTypeCode, "eventTypeCode");
    }

    /// A bare pattern binding (no id, version or filter).
    public static EventTypeBinding of(String eventTypeCode) {
        return new EventTypeBinding(null, eventTypeCode, null, null);
    }

    /// Whether this pattern matches `code`: same number of `:` segments, and
    /// every pattern segment is `*` or equal to the event's segment. `*`
    /// never spans segments and there is no `**`.
    public boolean matches(String code) {
        String[] pattern = eventTypeCode.split(":", -1);
        String[] segments = (code == null ? "" : code).split(":", -1);
        if (pattern.length != segments.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (!pattern[i].equals("*") && !pattern[i].equals(segments[i])) return false;
        }
        return true;
    }
}

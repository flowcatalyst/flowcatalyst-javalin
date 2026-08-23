package io.flowcatalyst.platform.event;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// One stored event — the CloudEvents 1.0 envelope (spec §1). Rows are
/// written only by the unit-of-work sink and projected by the stream
/// processor; this aggregate is read-only, so it has no transitions — just
/// the row shape of either table.
///
/// `projection` is the read-side columns (`msg_events_read`) and is `null`
/// when the row was read from the write-side `msg_events`; `context` is the
/// write-side `context_data` and is **empty on every read-side row**, because
/// the projection drops it. `data` is `null` when the column is `NULL`,
/// empty or the JSON literal `null`. The other optional strings are `null` when the column is `NULL`.
public record Event(
        String id,
        String specVersion,
        String type,
        String source,
        String subject,
        Instant time,
        JsonNode data,
        List<ContextEntry> context,
        String deduplicationId,
        String clientId,
        String messageGroup,
        String correlationId,
        String causationId,
        Instant createdAt,
        Projection projection) implements HasId {

    public Event {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(createdAt, "createdAt");
        context = context == null ? List.of() : List.copyOf(context);
        if (data != null && data.isNull()) data = null;
    }

    /// One `{key, value}` pair of the write-side `context_data` array.
    public record ContextEntry(String key, String value) {
        public ContextEntry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /// The read-side columns the stream processor derives from the type
    /// (`application:subdomain:aggregate:verb`, spec §9): `application` is the
    /// first segment and never `null`; `subdomain` / `aggregate` are `null`
    /// when the type has fewer segments; `projectedAt` is when the row landed.
    public record Projection(String application, String subdomain, String aggregate, Instant projectedAt) {
        public Projection {
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(projectedAt, "projectedAt");
        }
    }

    /// Platform-scoped: no client dimension (spec §8) — visible to any viewer.
    public boolean isPlatformScoped() {
        return clientId == null;
    }
}

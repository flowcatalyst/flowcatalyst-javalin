package io.flowcatalyst.sdk.usecase;

import java.time.Instant;

/// The contract every emitted domain event satisfies.
///
/// An event is a record that carries its CloudEvents-shaped [EventMetadata]
/// plus the event-specific fields. The metadata accessors below default to
/// the embedded metadata; an event overrides one only when the value is
/// derived rather than stored (e.g. a `messageGroup()` computed from the
/// aggregate id, or a `subject()` built from a code rather than an id).
///
/// ```java
/// public record EventTypeCreated(EventMetadata metadata, String eventTypeId, String code, String name)
///         implements DomainEvent {
///     @Override public Object data() { return new Data(eventTypeId, code, name); }
///     private record Data(String eventTypeId, String code, String name) {}
/// }
/// ```
public interface DomainEvent {

    /// The CloudEvents envelope (id, type, source, subject, time, trace ids).
    EventMetadata metadata();

    /// The event-specific payload. Must serialise to a JSON object; return a
    /// record (or a `Map`). Sinks serialise it with the platform's
    /// `ObjectMapper` and store it in the `data` column / payload field.
    /// `null` is written as `{}`.
    Object data();

    default String eventId() { return metadata().eventId(); }

    /// e.g. `platform:admin:eventtype:created`
    default String eventType() { return metadata().type(); }

    /// CloudEvents spec version, `1.0`.
    default String specVersion() { return metadata().specVersion(); }

    /// e.g. `platform:admin`
    default String source() { return metadata().source(); }

    /// e.g. `platform.eventtype.evt_01H…`
    default String subject() { return metadata().subject(); }

    default Instant time() { return metadata().occurredAt(); }

    default String principalId() { return metadata().principalId(); }

    default String correlationId() { return metadata().correlationId(); }

    default String causationId() { return metadata().causationId(); }

    default String executionId() { return metadata().executionId(); }

    /// Ordering key for downstream FIFO delivery; `null` when the event has no
    /// ordering requirement.
    default String messageGroup() { return metadata().messageGroup(); }
}

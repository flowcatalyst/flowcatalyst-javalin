package io.flowcatalyst.sdk.usecase;

import io.flowcatalyst.sdk.tsid.Tsid;

import java.time.Instant;
import java.util.Objects;

/// The CloudEvents-shaped envelope embedded in every [DomainEvent].
///
/// `eventId` is a raw 13-character TSID (the `msg_events.id` format);
/// `correlationId`, `causationId`, `principalId`, `executionId` and
/// `messageGroup` are optional — `null` means "not set" and sinks store SQL
/// `NULL` for them (the Go implementation uses the empty string for the same
/// purpose; sinks treat both identically).
///
/// @param eventId       raw 13-char TSID, unique per event
/// @param specVersion   CloudEvents spec version, always `1.0`
/// @param source        e.g. `platform:admin`
/// @param type          e.g. `platform:admin:eventtype:created`
/// @param subject       e.g. `platform.eventtype.evt_01H…`
/// @param occurredAt    when the event happened (UTC instant)
/// @param correlationId request/trace correlation id, propagated from the [ExecutionContext]
/// @param causationId   id of the event that caused this one, or `null`
/// @param principalId   acting principal, or `null`
/// @param executionId   unique per use-case invocation
/// @param messageGroup  downstream FIFO ordering key, or `null`
public record EventMetadata(
        String eventId,
        String specVersion,
        String source,
        String type,
        String subject,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String principalId,
        String executionId,
        String messageGroup) {

    public static final String SPEC_VERSION = "1.0";

    public EventMetadata {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (specVersion == null || specVersion.isBlank()) {
            specVersion = SPEC_VERSION;
        }
    }

    /// Builds metadata for a new event from the executing context plus the
    /// per-event fields: fresh event id, `now()` as the occurrence time, trace
    /// ids copied from `ec`, no message group.
    public static EventMetadata of(ExecutionContext ec, String type, String source, String subject) {
        Objects.requireNonNull(ec, "ec");
        return new EventMetadata(
                Tsid.generate(),
                SPEC_VERSION,
                source,
                type,
                subject,
                Instant.now(),
                ec.correlationId(),
                ec.causationId(),
                ec.principalId(),
                ec.executionId(),
                null);
    }

    /// Same metadata with a message group set.
    public EventMetadata withMessageGroup(String group) {
        return new EventMetadata(eventId, specVersion, source, type, subject, occurredAt,
                correlationId, causationId, principalId, executionId, group);
    }
}

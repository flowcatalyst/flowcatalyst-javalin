package io.flowcatalyst.function;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/// The CloudEvents-shaped envelope the platform stores, carried by an
/// [EventInvocation]. `data` is cloned in the constructor and again by
/// [#data()].
///
/// @param id              the event's own id
/// @param type            the CloudEvents `type`
/// @param source          the CloudEvents `source`
/// @param subject         the CloudEvents `subject`
/// @param time             when the event occurred
/// @param dataContentType the media type of `data`
/// @param data            the event payload
/// @param correlationId   links this event to the request/flow that caused it
/// @param causationId     the id of the event that directly caused this one
/// @param messageGroup    the ordering group this event belongs to
/// @param dedupId         the id the platform deduplicated this event on
public record Event(
        String id,
        String type,
        String source,
        String subject,
        Instant time,
        String dataContentType,
        byte[] data,
        String correlationId,
        String causationId,
        String messageGroup,
        String dedupId) {

    public Event {
        data = Copies.bytes(data);
    }

    @Override
    public byte[] data() {
        return Copies.bytes(data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Event other)) return false;
        return Objects.equals(id, other.id)
                && Objects.equals(type, other.type)
                && Objects.equals(source, other.source)
                && Objects.equals(subject, other.subject)
                && Objects.equals(time, other.time)
                && Objects.equals(dataContentType, other.dataContentType)
                && Arrays.equals(data, other.data)
                && Objects.equals(correlationId, other.correlationId)
                && Objects.equals(causationId, other.causationId)
                && Objects.equals(messageGroup, other.messageGroup)
                && Objects.equals(dedupId, other.dedupId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, type, source, subject, time, dataContentType,
                correlationId, causationId, messageGroup, dedupId);
        return 31 * result + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "Event[id=" + id + ", type=" + type + ", source=" + source + ", subject=" + subject
                + ", time=" + time + ", dataContentType=" + dataContentType
                + ", data.length=" + (data == null ? 0 : data.length)
                + ", correlationId=" + correlationId + ", causationId=" + causationId
                + ", messageGroup=" + messageGroup + ", dedupId=" + dedupId + "]";
    }
}

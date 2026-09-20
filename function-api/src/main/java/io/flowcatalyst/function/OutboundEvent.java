package io.flowcatalyst.function;

import java.util.Arrays;
import java.util.Objects;

/// An event a function emits through [Events#emit]. `data` is cloned in the
/// constructor and again by [#data()].
///
/// @param type            the CloudEvents `type`
/// @param source          the CloudEvents `source`
/// @param subject         the CloudEvents `subject`
/// @param dataContentType the media type of `data`
/// @param data            the event payload
/// @param correlationId   links this event to the request/flow that caused it
/// @param causationId     the id of the event that directly caused this one
/// @param messageGroup    the ordering group this event belongs to
/// @param dedupId         the id the platform should deduplicate this event on
public record OutboundEvent(
        String type,
        String source,
        String subject,
        String dataContentType,
        byte[] data,
        String correlationId,
        String causationId,
        String messageGroup,
        String dedupId) {

    public OutboundEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dedupId, "dedupId");
        if (dedupId.isBlank()) {
            throw new IllegalArgumentException("dedupId must not be blank");
        }
        data = Copies.bytes(data);
    }

    @Override
    public byte[] data() {
        return Copies.bytes(data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboundEvent other)) return false;
        return Objects.equals(type, other.type)
                && Objects.equals(source, other.source)
                && Objects.equals(subject, other.subject)
                && Objects.equals(dataContentType, other.dataContentType)
                && Arrays.equals(data, other.data)
                && Objects.equals(correlationId, other.correlationId)
                && Objects.equals(causationId, other.causationId)
                && Objects.equals(messageGroup, other.messageGroup)
                && Objects.equals(dedupId, other.dedupId);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, source, subject, dataContentType,
                correlationId, causationId, messageGroup, dedupId);
        return 31 * result + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "OutboundEvent[type=" + type + ", source=" + source + ", subject=" + subject
                + ", dataContentType=" + dataContentType + ", data.length=" + (data == null ? 0 : data.length)
                + ", correlationId=" + correlationId + ", causationId=" + causationId
                + ", messageGroup=" + messageGroup + ", dedupId=" + dedupId + "]";
    }
}

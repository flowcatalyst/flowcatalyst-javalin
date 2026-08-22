package io.flowcatalyst.platform.cors.operations;

import io.flowcatalyst.platform.cors.CorsOrigin;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The CORS allowlist's domain events (spec §7): the type strings, the
/// source, the subject / message-group builders and one record per event.
/// Every event has a static `of(…)` factory that takes the execution
/// context plus the aggregate, so operations never assemble metadata or
/// payload fields by hand; the `data()` records are the wire payloads,
/// field names verbatim. Every event carries the message group
/// `platform:cors:{id}` so one origin's events are delivered in order.
///
/// These two events are also the CORS filter's cache-invalidation signal
/// (spec §9).
public final class CorsOriginEvents {

    public static final String SOURCE = "platform:admin";

    public static final String ORIGIN_ADDED = "platform:admin:cors:origin-added";
    public static final String ORIGIN_DELETED = "platform:admin:cors:origin-deleted";

    private CorsOriginEvents() {
    }

    /// `platform.cors.{id}` — the subject of every event.
    public static String subjectFor(String originId) {
        return EventConventions.buildSubject("platform", "cors", originId);
    }

    /// `platform:cors:{id}` — the message group of every event.
    public static String messageGroupFor(String originId) {
        return EventConventions.buildMessageGroup("platform", "cors", originId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, CorsOrigin o) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(o.id()));
    }

    /// Emitted when an origin is added to the allowlist.
    public record CorsOriginAdded(EventMetadata metadata, String originId, String origin) implements DomainEvent {

        public static CorsOriginAdded of(ExecutionContext ec, CorsOrigin o) {
            return new CorsOriginAdded(metadataFor(ec, ORIGIN_ADDED, o), o.id(), o.origin());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(originId);
        }

        @Override
        public Object data() {
            return new Data(originId, origin);
        }

        private record Data(String originId, String origin) {
        }
    }

    /// Emitted when an origin is removed from the allowlist.
    public record CorsOriginDeleted(EventMetadata metadata, String originId, String origin) implements DomainEvent {

        public static CorsOriginDeleted of(ExecutionContext ec, CorsOrigin o) {
            return new CorsOriginDeleted(metadataFor(ec, ORIGIN_DELETED, o), o.id(), o.origin());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(originId);
        }

        @Override
        public Object data() {
            return new Data(originId, origin);
        }

        private record Data(String originId, String origin) {
        }
    }
}

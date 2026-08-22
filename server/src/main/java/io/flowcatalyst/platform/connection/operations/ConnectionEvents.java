package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The connection aggregate's domain events (spec §7): the type strings,
/// the source, the subject / message-group builders and one record per
/// event. Every event has a static `of(…)` factory that takes the execution
/// context plus the aggregate, so operations never assemble metadata or
/// payload fields by hand; the `data()` records are the wire payloads,
/// field names verbatim. Every event carries the per-connection message
/// group so one connection's events are delivered in order.
public final class ConnectionEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:connection:created";
    public static final String UPDATED = "platform:admin:connection:updated";
    public static final String DELETED = "platform:admin:connection:deleted";

    private ConnectionEvents() {
    }

    /// `platform.connection.{id}` — the subject of every event.
    public static String subjectFor(String connectionId) {
        return EventConventions.buildSubject("platform", "connection", connectionId);
    }

    /// `platform:connection:{id}` — the message group of every event.
    public static String groupFor(String connectionId) {
        return EventConventions.buildMessageGroup("platform", "connection", connectionId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, Connection c) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(c.id()));
    }

    /// Emitted on create.
    public record ConnectionCreated(EventMetadata metadata, String connectionId, String code, String name)
            implements DomainEvent {

        public static ConnectionCreated of(ExecutionContext ec, Connection c) {
            return new ConnectionCreated(metadataFor(ec, CREATED, c), c.id(), c.code(), c.name());
        }

        @Override
        public String messageGroup() {
            return groupFor(connectionId);
        }

        @Override
        public Object data() {
            return new Data(connectionId, code, name);
        }

        private record Data(String connectionId, String code, String name) {
        }
    }

    /// Emitted on update and on the pause / activate flips (spec §7, open
    /// question 6: the payload carries no status).
    public record ConnectionUpdated(EventMetadata metadata, String connectionId, String name) implements DomainEvent {

        public static ConnectionUpdated of(ExecutionContext ec, Connection c) {
            return new ConnectionUpdated(metadataFor(ec, UPDATED, c), c.id(), c.name());
        }

        @Override
        public String messageGroup() {
            return groupFor(connectionId);
        }

        @Override
        public Object data() {
            return new Data(connectionId, name);
        }

        private record Data(String connectionId, String name) {
        }
    }

    /// Emitted on delete.
    public record ConnectionDeleted(EventMetadata metadata, String connectionId, String code) implements DomainEvent {

        public static ConnectionDeleted of(ExecutionContext ec, Connection c) {
            return new ConnectionDeleted(metadataFor(ec, DELETED, c), c.id(), c.code());
        }

        @Override
        public String messageGroup() {
            return groupFor(connectionId);
        }

        @Override
        public Object data() {
            return new Data(connectionId, code);
        }

        private record Data(String connectionId, String code) {
        }
    }
}

package io.flowcatalyst.platform.connection.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

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
    public static final String SYNCED = "platform:admin:connection:synced";

    /// The sync rollup's bare (no-application) message group — falls back
    /// to this when there is genuinely no application in scope (mirrors
    /// [io.flowcatalyst.platform.subscription.operations.SubscriptionEvents#SYNC_MESSAGE_GROUP]).
    public static final String SYNC_MESSAGE_GROUP = "platform:connections";

    private ConnectionEvents() {
    }

    /// `platform.connection.{id}` — the subject of every per-connection event.
    public static String subjectFor(String connectionId) {
        return EventConventions.buildSubject("platform", "connection", connectionId);
    }

    /// `platform:connection:{id}` — the message group of every per-connection event.
    public static String groupFor(String connectionId) {
        return EventConventions.buildMessageGroup("platform", "connection", connectionId);
    }

    /// `platform.connections.{applicationCode}` — the subject of the sync
    /// rollup (hand-off "Connection sync (new)"; note the plural
    /// `connections`, distinct from the per-connection [#subjectFor]).
    public static String syncSubjectFor(String applicationCode) {
        return EventConventions.buildSubject("platform", "connections", applicationCode);
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

    /// The rollup emitted by [SyncConnections]: subject
    /// `platform.connections.{applicationCode}`, message group per
    /// application — falls back to the bare [#SYNC_MESSAGE_GROUP] when
    /// there's no application in scope (hand-off "Connection sync (new)";
    /// mirrors [io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionsSynced],
    /// plus `clientId` — a connection sync is also scoped to one client).
    public record ConnectionsSynced(EventMetadata metadata, String applicationCode, String clientId, int created,
                                    int updated, int deleted, List<String> syncedCodes) implements DomainEvent {

        public ConnectionsSynced {
            syncedCodes = syncedCodes == null ? List.of() : List.copyOf(syncedCodes);
        }

        public static ConnectionsSynced of(ExecutionContext ec, String applicationCode, String clientId, int created,
                                           int updated, int deleted, List<String> syncedCodes) {
            return new ConnectionsSynced(EventMetadata.of(ec, SYNCED, SOURCE, syncSubjectFor(applicationCode)),
                    applicationCode, clientId, created, updated, deleted, syncedCodes);
        }

        @Override
        public String messageGroup() {
            return applicationCode == null || applicationCode.isBlank()
                    ? SYNC_MESSAGE_GROUP
                    : SYNC_MESSAGE_GROUP + ":" + applicationCode;
        }

        @Override
        public Object data() {
            return new Data(applicationCode, clientId, created, updated, deleted, syncedCodes);
        }

        private record Data(String applicationCode, String clientId, int created, int updated, int deleted,
                            List<String> syncedCodes) {
        }
    }
}

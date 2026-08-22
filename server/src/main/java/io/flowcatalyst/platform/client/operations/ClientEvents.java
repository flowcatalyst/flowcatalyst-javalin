package io.flowcatalyst.platform.client.operations;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The client aggregate's domain events (spec §7): the type strings, the
/// source, the subject / message-group builders and one record per event.
/// Every event has a static `of(…)` factory that takes the execution
/// context plus the aggregate, so operations never assemble metadata or
/// payload fields by hand; the `data()` records are the wire payloads,
/// field names verbatim. Every event carries the message group
/// `platform:client:{id}` so one client's events are delivered in order.
public final class ClientEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:client:created";
    public static final String UPDATED = "platform:admin:client:updated";
    public static final String ACTIVATED = "platform:admin:client:activated";
    public static final String SUSPENDED = "platform:admin:client:suspended";
    public static final String NOTE_ADDED = "platform:admin:client:note-added";
    public static final String DELETED = "platform:admin:client:deleted";

    private ClientEvents() {
    }

    /// `platform.client.{id}` — the subject of every event.
    public static String subjectFor(String clientId) {
        return EventConventions.buildSubject("platform", "client", clientId);
    }

    /// `platform:client:{id}` — the message group of every event.
    public static String messageGroupFor(String clientId) {
        return EventConventions.buildMessageGroup("platform", "client", clientId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, Client c) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(c.id()));
    }

    /// Emitted on create.
    public record ClientCreated(EventMetadata metadata, String clientId, String name, String identifier)
            implements DomainEvent {

        public static ClientCreated of(ExecutionContext ec, Client c) {
            return new ClientCreated(metadataFor(ec, CREATED, c), c.id(), c.name(), c.identifier());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(clientId);
        }

        @Override
        public Object data() {
            return new Data(clientId, name, identifier);
        }

        private record Data(String clientId, String name, String identifier) {
        }
    }

    /// Emitted on update; carries the name after the change.
    public record ClientUpdated(EventMetadata metadata, String clientId, String name) implements DomainEvent {

        public static ClientUpdated of(ExecutionContext ec, Client c) {
            return new ClientUpdated(metadataFor(ec, UPDATED, c), c.id(), c.name());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(clientId);
        }

        @Override
        public Object data() {
            return new Data(clientId, name);
        }

        private record Data(String clientId, String name) {
        }
    }

    /// Emitted when a client is (re)activated.
    public record ClientActivated(EventMetadata metadata, String clientId) implements DomainEvent {

        public static ClientActivated of(ExecutionContext ec, Client c) {
            return new ClientActivated(metadataFor(ec, ACTIVATED, c), c.id());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(clientId);
        }

        @Override
        public Object data() {
            return new Data(clientId);
        }

        private record Data(String clientId) {
        }
    }

    /// Emitted when a client is suspended; `reason` is the command's, verbatim.
    public record ClientSuspended(EventMetadata metadata, String clientId, String reason) implements DomainEvent {

        public static ClientSuspended of(ExecutionContext ec, Client c, String reason) {
            return new ClientSuspended(metadataFor(ec, SUSPENDED, c), c.id(), reason);
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(clientId);
        }

        @Override
        public Object data() {
            return new Data(clientId, reason);
        }

        private record Data(String clientId, String reason) {
        }
    }

    /// Emitted when a note is appended.
    public record ClientNoteAdded(EventMetadata metadata, String clientId, String category, String text)
            implements DomainEvent {

        public static ClientNoteAdded of(ExecutionContext ec, Client c, String category, String text) {
            return new ClientNoteAdded(metadataFor(ec, NOTE_ADDED, c), c.id(), category, text);
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(clientId);
        }

        @Override
        public Object data() {
            return new Data(clientId, category, text);
        }

        private record Data(String clientId, String category, String text) {
        }
    }

    /// Emitted on delete (and by the deactivate alias).
    public record ClientDeleted(EventMetadata metadata, String clientId, String identifier) implements DomainEvent {

        public static ClientDeleted of(ExecutionContext ec, Client c) {
            return new ClientDeleted(metadataFor(ec, DELETED, c), c.id(), c.identifier());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(clientId);
        }

        @Override
        public Object data() {
            return new Data(clientId, identifier);
        }

        private record Data(String clientId, String identifier) {
        }
    }
}

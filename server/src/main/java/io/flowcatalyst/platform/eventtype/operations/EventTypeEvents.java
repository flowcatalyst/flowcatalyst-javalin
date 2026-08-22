package io.flowcatalyst.platform.eventtype.operations;

import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The event-type aggregate's domain events (spec §8): the type strings,
/// the source, the subject builders and one record per event. Every event
/// has a static `of(…)` factory that takes the execution context plus the
/// aggregate, so operations never assemble metadata or payload fields by
/// hand; the `data()` records are the wire payloads, field names verbatim.
public final class EventTypeEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:eventtype:created";
    public static final String UPDATED = "platform:admin:eventtype:updated";
    public static final String DELETED = "platform:admin:eventtype:deleted";
    public static final String ARCHIVED = "platform:admin:eventtype:archived";
    public static final String SCHEMA_ADDED = "platform:admin:eventtype:schema-added";
    public static final String SCHEMA_FINALISED = "platform:admin:eventtype:schema-finalised";
    public static final String SCHEMA_DEPRECATED = "platform:admin:eventtype:schema-deprecated";
    public static final String SYNCED = "platform:admin:eventtypes:synced";

    private EventTypeEvents() {
    }

    /// `platform.eventtype.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String eventTypeId) {
        return EventConventions.buildSubject("platform", "eventtype", eventTypeId);
    }

    /// `platform.eventtypes.{applicationCode}` — the subject of the sync rollup.
    public static String syncSubjectFor(String applicationCode) {
        return EventConventions.buildSubject("platform", "eventtypes", applicationCode);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, EventType et) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(et.id()));
    }

    /// Emitted on create (and per created row by sync).
    public record EventTypeCreated(
            EventMetadata metadata, String eventTypeId, String code, String name, String description,
            String application, String subdomain, String aggregate, String eventName, String clientId)
            implements DomainEvent {

        public static EventTypeCreated of(ExecutionContext ec, EventType et) {
            return new EventTypeCreated(metadataFor(ec, CREATED, et), et.id(), et.code(), et.name(), et.description(),
                    et.application(), et.subdomain(), et.aggregate(), et.eventName(), et.clientId());
        }

        @Override
        public Object data() {
            return new Data(eventTypeId, code, name, description, application, subdomain, aggregate, eventName, clientId);
        }

        private record Data(String eventTypeId, String code, String name, String description, String application,
                            String subdomain, String aggregate, String eventName, String clientId) {
        }
    }

    /// Emitted on update (and per updated row by sync).
    public record EventTypeUpdated(EventMetadata metadata, String eventTypeId, String name, String description)
            implements DomainEvent {

        public static EventTypeUpdated of(ExecutionContext ec, EventType et) {
            return new EventTypeUpdated(metadataFor(ec, UPDATED, et), et.id(), et.name(), et.description());
        }

        @Override
        public Object data() {
            return new Data(eventTypeId, name, description);
        }

        private record Data(String eventTypeId, String name, String description) {
        }
    }

    /// Emitted on delete (and per removed row by sync).
    public record EventTypeDeleted(EventMetadata metadata, String eventTypeId, String code) implements DomainEvent {

        public static EventTypeDeleted of(ExecutionContext ec, EventType et) {
            return new EventTypeDeleted(metadataFor(ec, DELETED, et), et.id(), et.code());
        }

        @Override
        public Object data() {
            return new Data(eventTypeId, code);
        }

        private record Data(String eventTypeId, String code) {
        }
    }

    /// Emitted when an event type transitions `CURRENT` → `ARCHIVED`.
    public record EventTypeArchived(EventMetadata metadata, String eventTypeId, String code) implements DomainEvent {

        public static EventTypeArchived of(ExecutionContext ec, EventType et) {
            return new EventTypeArchived(metadataFor(ec, ARCHIVED, et), et.id(), et.code());
        }

        @Override
        public Object data() {
            return new Data(eventTypeId, code);
        }

        private record Data(String eventTypeId, String code) {
        }
    }

    /// Emitted when a schema version is added; on the wire the version string
    /// is `specVersion`.
    public record EventTypeSchemaAdded(EventMetadata metadata, String eventTypeId, String version)
            implements DomainEvent {

        public static EventTypeSchemaAdded of(ExecutionContext ec, EventType et, String version) {
            return new EventTypeSchemaAdded(metadataFor(ec, SCHEMA_ADDED, et), et.id(), version);
        }

        @Override
        public Object data() {
            return new Data(eventTypeId, version);
        }

        private record Data(String eventTypeId, String specVersion) {
        }
    }

    /// Emitted when a spec version transitions `FINALISING` → `CURRENT`;
    /// `deprecatedVersion` names the same-major sibling that was
    /// auto-deprecated, or is `null` (and omitted on the wire).
    public record EventTypeSchemaFinalised(EventMetadata metadata, String eventTypeId, String version,
                                           String deprecatedVersion) implements DomainEvent {

        public static EventTypeSchemaFinalised of(ExecutionContext ec, EventType et, String version, String deprecatedVersion) {
            return new EventTypeSchemaFinalised(metadataFor(ec, SCHEMA_FINALISED, et), et.id(), version, deprecatedVersion);
        }

        @Override
        public Object data() {
            return new Data(eventTypeId, version, deprecatedVersion);
        }

        private record Data(String eventTypeId, String specVersion, String deprecatedVersion) {
        }
    }

    /// Emitted when a spec version transitions `CURRENT` → `DEPRECATED`.
    public record EventTypeSchemaDeprecated(EventMetadata metadata, String eventTypeId, String version)
            implements DomainEvent {

        public static EventTypeSchemaDeprecated of(ExecutionContext ec, EventType et, String version) {
            return new EventTypeSchemaDeprecated(metadataFor(ec, SCHEMA_DEPRECATED, et), et.id(), version);
        }

        @Override
        public Object data() {
            return new Data(eventTypeId, version);
        }

        private record Data(String eventTypeId, String specVersion) {
        }
    }

    /// The rollup emitted by [SyncEventTypes]: subject
    /// `platform.eventtypes.{applicationCode}`, message group
    /// `platform:eventtypes:{applicationCode}` so one application's syncs
    /// are delivered in order.
    public record EventTypesSynced(EventMetadata metadata, String applicationCode, int created, int updated,
                                   int deleted, List<String> syncedCodes) implements DomainEvent {

        public EventTypesSynced {
            syncedCodes = syncedCodes == null ? List.of() : List.copyOf(syncedCodes);
        }

        public static EventTypesSynced of(ExecutionContext ec, String applicationCode, int created, int updated,
                                          int deleted, List<String> syncedCodes) {
            return new EventTypesSynced(EventMetadata.of(ec, SYNCED, SOURCE, syncSubjectFor(applicationCode)),
                    applicationCode, created, updated, deleted, syncedCodes);
        }

        @Override
        public String messageGroup() {
            return EventConventions.buildMessageGroup("platform", "eventtypes", applicationCode);
        }

        @Override
        public Object data() {
            return new Data(applicationCode, created, updated, deleted, syncedCodes);
        }

        private record Data(String applicationCode, int created, int updated, int deleted, List<String> syncedCodes) {
        }
    }
}

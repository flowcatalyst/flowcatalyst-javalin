package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigAccess;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The platform-config domain events (spec §8): the type strings, the source,
/// the subject / message-group builders and one record per event. Both
/// aggregates (config values and access grants) publish on the
/// `platform.platformconfig.{id}` subject namespace, and every event carries
/// the per-aggregate message group `platform:platformconfig:{id}`. Every
/// event has a static `of(…)` factory taking the execution context plus the
/// aggregate; the `data()` records are the wire payloads, field names
/// verbatim. A `property-set` payload never carries the value (secrets).
public final class PlatformConfigEvents {

    public static final String SOURCE = "platform:admin";

    public static final String PROPERTY_SET = "platform:admin:platform-config:property-set";
    public static final String ACCESS_GRANTED = "platform:admin:platform-config:access-granted";
    public static final String ACCESS_REVOKED = "platform:admin:platform-config:access-revoked";

    private PlatformConfigEvents() {
    }

    /// `platform.platformconfig.{id}` — the subject of every event here.
    public static String subjectFor(String id) {
        return EventConventions.buildSubject("platform", "platformconfig", id);
    }

    /// `platform:platformconfig:{id}` — the FIFO key of every event here.
    public static String messageGroupFor(String id) {
        return EventConventions.buildMessageGroup("platform", "platformconfig", id);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String id) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(id));
    }

    /// Emitted when a config value is created or replaced.
    public record PropertySet(EventMetadata metadata, String configId, String applicationCode, String section,
                              String property) implements DomainEvent {

        public static PropertySet of(ExecutionContext ec, PlatformConfig c) {
            return new PropertySet(metadataFor(ec, PROPERTY_SET, c.id()), c.id(), c.applicationCode(), c.section(), c.property());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(configId);
        }

        @Override
        public Object data() {
            return new Data(configId, applicationCode, section, property);
        }

        private record Data(String configId, String applicationCode, String section, String property) {
        }
    }

    /// Emitted when a grant is created or re-granted.
    public record AccessGranted(EventMetadata metadata, String accessId, String applicationCode, String roleCode,
                                boolean canWrite) implements DomainEvent {

        public static AccessGranted of(ExecutionContext ec, ConfigAccess a) {
            return new AccessGranted(metadataFor(ec, ACCESS_GRANTED, a.id()), a.id(), a.applicationCode(), a.roleCode(), a.canWrite());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(accessId);
        }

        @Override
        public Object data() {
            return new Data(accessId, applicationCode, roleCode, canWrite);
        }

        private record Data(String accessId, String applicationCode, String roleCode, boolean canWrite) {
        }
    }

    /// Emitted when a grant is revoked.
    public record AccessRevoked(EventMetadata metadata, String accessId, String applicationCode, String roleCode)
            implements DomainEvent {

        public static AccessRevoked of(ExecutionContext ec, ConfigAccess a) {
            return new AccessRevoked(metadataFor(ec, ACCESS_REVOKED, a.id()), a.id(), a.applicationCode(), a.roleCode());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(accessId);
        }

        @Override
        public Object data() {
            return new Data(accessId, applicationCode, roleCode);
        }

        private record Data(String accessId, String applicationCode, String roleCode) {
        }
    }
}

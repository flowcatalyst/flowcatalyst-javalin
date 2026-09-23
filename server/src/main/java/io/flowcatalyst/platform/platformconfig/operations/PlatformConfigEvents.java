package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The platform-config domain events: the type string, the source, the
/// subject / message-group builders and the one event a config value
/// publishes on the `platform.platformconfig.{id}` subject namespace, with
/// the per-aggregate message group `platform:platformconfig:{id}`. The
/// access-grant aggregate and its events are withdrawn
/// (`docs/spec/config-permissions.md` §A.3 — permissions come from roles,
/// not a grant table). A `property-set` payload never carries the value
/// (secrets).
public final class PlatformConfigEvents {

    public static final String SOURCE = "platform:admin";

    public static final String PROPERTY_SET = "platform:admin:platform-config:property-set";

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
}

package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The portal-app aggregate's domain events (spec `portal-apps.md` §3):
/// source `platform:portal`, subject `platform.portal-app.{id}`, message
/// group `platform:portal-app:{id}`. Every event carries the same
/// `{portalAppId, clientId, code, name}` payload — field names verbatim —
/// so `created`/`updated`/`deleted` share one `Data` shape.
public final class PortalAppEvents {

    public static final String SOURCE = "platform:portal";

    public static final String CREATED = "platform:portal:app:created";
    public static final String UPDATED = "platform:portal:app:updated";
    public static final String DELETED = "platform:portal:app:deleted";

    private PortalAppEvents() {
    }

    private static String subjectFor(String appId) {
        return EventConventions.buildSubject("platform", "portal-app", appId);
    }

    private static String messageGroupFor(String appId) {
        return EventConventions.buildMessageGroup("platform", "portal-app", appId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String appId) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(appId)).withMessageGroup(messageGroupFor(appId));
    }

    public record PortalAppCreated(EventMetadata metadata, String portalAppId, String clientId, String code, String name)
            implements DomainEvent {

        public static PortalAppCreated of(ExecutionContext ec, PortalApp app) {
            return new PortalAppCreated(metadataFor(ec, CREATED, app.id()), app.id(), app.clientId(), app.code(), app.name());
        }

        @Override
        public Object data() {
            return new Data(portalAppId, clientId, code, name);
        }

        private record Data(String portalAppId, String clientId, String code, String name) {
        }
    }

    public record PortalAppUpdated(EventMetadata metadata, String portalAppId, String clientId, String code, String name)
            implements DomainEvent {

        public static PortalAppUpdated of(ExecutionContext ec, PortalApp app) {
            return new PortalAppUpdated(metadataFor(ec, UPDATED, app.id()), app.id(), app.clientId(), app.code(), app.name());
        }

        @Override
        public Object data() {
            return new Data(portalAppId, clientId, code, name);
        }

        private record Data(String portalAppId, String clientId, String code, String name) {
        }
    }

    public record PortalAppDeleted(EventMetadata metadata, String portalAppId, String clientId, String code, String name)
            implements DomainEvent {

        public static PortalAppDeleted of(ExecutionContext ec, PortalApp app) {
            return new PortalAppDeleted(metadataFor(ec, DELETED, app.id()), app.id(), app.clientId(), app.code(), app.name());
        }

        @Override
        public Object data() {
            return new Data(portalAppId, clientId, code, name);
        }

        private record Data(String portalAppId, String clientId, String code, String name) {
        }
    }
}

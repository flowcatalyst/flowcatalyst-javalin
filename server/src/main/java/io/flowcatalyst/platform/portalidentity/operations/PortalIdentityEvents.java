package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The portal-identity aggregate's domain events (spec `auth-identity.md`
/// §5.7): source `platform:portal`, subject
/// `platform.portal-identity.{id}`, message group
/// `platform:portal-identity:{id}`. Event field names are never
/// `principalId` — that accessor is reserved for the *actor*
/// ([DomainEvent#principalId()]); an event about the affected identity
/// names it `identityId`, enforced by `DomainEventContractTest`.
public final class PortalIdentityEvents {

    public static final String SOURCE = "platform:portal";

    public static final String ENSURED = "platform:portal:identity:ensured";
    public static final String STATUS_SET = "platform:portal:identity:status-set";
    public static final String DELETED = "platform:portal:identity:deleted";

    private PortalIdentityEvents() {
    }

    private static String subjectFor(String identityId) {
        return EventConventions.buildSubject("platform", "portal-identity", identityId);
    }

    private static String messageGroupFor(String identityId) {
        return EventConventions.buildMessageGroup("platform", "portal-identity", identityId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String identityId) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(identityId)).withMessageGroup(messageGroupFor(identityId));
    }

    /// `identitySource` — never `source` — because a record component named
    /// `source` would shadow [DomainEvent#source()]'s inherited default
    /// (the CloudEvents envelope source, `platform:portal`, which
    /// `PlatformSink` writes to `msg_events.source`) with this aggregate's
    /// own `INVITE`/`JIT` value, exactly the `principalId`-shadowing defect
    /// `DomainEventContractTest` guards against, just on a different
    /// accessor that test does not scan for. The wire payload (`data()`)
    /// still calls its field `source` (spec §5.7's JSON key) — that is a
    /// *different* record ([Data]), so no shadowing there.
    public record PortalIdentityEnsured(
            EventMetadata metadata, String identityId, String clientId, String email, boolean created, String identitySource)
            implements DomainEvent {

        public static PortalIdentityEnsured of(ExecutionContext ec, PortalIdentity pi, boolean created) {
            return new PortalIdentityEnsured(metadataFor(ec, ENSURED, pi.id()), pi.id(), pi.clientId(), pi.email(),
                    created, pi.source().name());
        }

        @Override
        public Object data() {
            return new Data(identityId, clientId, email, created, identitySource);
        }

        private record Data(String identityId, String clientId, String email, boolean created, String source) {
        }
    }

    public record PortalIdentityStatusSet(EventMetadata metadata, String identityId, String clientId, String status)
            implements DomainEvent {

        public static PortalIdentityStatusSet of(ExecutionContext ec, PortalIdentity pi) {
            return new PortalIdentityStatusSet(metadataFor(ec, STATUS_SET, pi.id()), pi.id(), pi.clientId(),
                    pi.status().name());
        }

        @Override
        public Object data() {
            return new Data(identityId, clientId, status);
        }

        private record Data(String identityId, String clientId, String status) {
        }
    }

    public record PortalIdentityDeleted(EventMetadata metadata, String identityId, String clientId, String email)
            implements DomainEvent {

        public static PortalIdentityDeleted of(ExecutionContext ec, PortalIdentity pi) {
            return new PortalIdentityDeleted(metadataFor(ec, DELETED, pi.id()), pi.id(), pi.clientId(), pi.email());
        }

        @Override
        public Object data() {
            return new Data(identityId, clientId, email);
        }

        private record Data(String identityId, String clientId, String email) {
        }
    }
}

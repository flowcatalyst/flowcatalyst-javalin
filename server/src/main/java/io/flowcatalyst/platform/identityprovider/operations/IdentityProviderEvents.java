package io.flowcatalyst.platform.identityprovider.operations;

import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The identity-provider aggregate's domain events (spec §7): the type
/// strings, the source, the subject / message-group builders and one record
/// per event. Every event has a static `of(…)` factory that takes the
/// execution context plus the aggregate, so operations never assemble
/// metadata or payload fields by hand; the `data()` records are the wire
/// payloads, field names verbatim. Every event carries the message group
/// `platform:identityprovider:{id}` so one provider's events are delivered
/// in order.
public final class IdentityProviderEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:identity-provider:created";
    public static final String UPDATED = "platform:admin:identity-provider:updated";
    public static final String DELETED = "platform:admin:identity-provider:deleted";

    private IdentityProviderEvents() {
    }

    /// `platform.identityprovider.{id}` — the subject of every event.
    public static String subjectFor(String identityProviderId) {
        return EventConventions.buildSubject("platform", "identityprovider", identityProviderId);
    }

    /// `platform:identityprovider:{id}` — the message group of every event.
    public static String messageGroupFor(String identityProviderId) {
        return EventConventions.buildMessageGroup("platform", "identityprovider", identityProviderId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, IdentityProvider ip) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(ip.id()));
    }

    /// Emitted on create.
    public record IdentityProviderCreated(EventMetadata metadata, String identityProviderId, String code)
            implements DomainEvent {

        public static IdentityProviderCreated of(ExecutionContext ec, IdentityProvider ip) {
            return new IdentityProviderCreated(metadataFor(ec, CREATED, ip), ip.id(), ip.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(identityProviderId);
        }

        @Override
        public Object data() {
            return new Data(identityProviderId, code);
        }

        private record Data(String identityProviderId, String code) {
        }
    }

    /// Emitted on every update (spec §7, open question 6).
    public record IdentityProviderUpdated(EventMetadata metadata, String identityProviderId, String code)
            implements DomainEvent {

        public static IdentityProviderUpdated of(ExecutionContext ec, IdentityProvider ip) {
            return new IdentityProviderUpdated(metadataFor(ec, UPDATED, ip), ip.id(), ip.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(identityProviderId);
        }

        @Override
        public Object data() {
            return new Data(identityProviderId, code);
        }

        private record Data(String identityProviderId, String code) {
        }
    }

    /// Emitted on delete.
    public record IdentityProviderDeleted(EventMetadata metadata, String identityProviderId, String code)
            implements DomainEvent {

        public static IdentityProviderDeleted of(ExecutionContext ec, IdentityProvider ip) {
            return new IdentityProviderDeleted(metadataFor(ec, DELETED, ip), ip.id(), ip.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(identityProviderId);
        }

        @Override
        public Object data() {
            return new Data(identityProviderId, code);
        }

        private record Data(String identityProviderId, String code) {
        }
    }
}

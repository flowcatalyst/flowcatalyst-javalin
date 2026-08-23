package io.flowcatalyst.platform.emaildomainmapping.operations;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The email-domain-mapping aggregate's domain events (spec §7): the type
/// strings, the source, the subject / message-group builders and one record
/// per event. Every event has a static `of(…)` factory that takes the
/// execution context plus the aggregate, so operations never assemble
/// metadata or payload fields by hand; the `data()` records are the wire
/// payloads, field names verbatim. Every event carries the message group
/// `platform:emaildomainmapping:{id}` so one mapping's events are delivered
/// in order.
public final class EmailDomainMappingEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:email-domain-mapping:created";
    public static final String UPDATED = "platform:admin:email-domain-mapping:updated";
    public static final String DELETED = "platform:admin:email-domain-mapping:deleted";
    public static final String PROVIDER_CHANGED = "platform:admin:email-domain-mapping:provider-changed";

    private EmailDomainMappingEvents() {
    }

    /// `platform.emaildomainmapping.{id}` — the subject of every event.
    public static String subjectFor(String mappingId) {
        return EventConventions.buildSubject("platform", "emaildomainmapping", mappingId);
    }

    /// `platform:emaildomainmapping:{id}` — the message group of every event.
    public static String messageGroupFor(String mappingId) {
        return EventConventions.buildMessageGroup("platform", "emaildomainmapping", mappingId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, EmailDomainMapping m) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(m.id()));
    }

    /// Emitted on create.
    public record EmailDomainMappingCreated(EventMetadata metadata, String mappingId, String emailDomain)
            implements DomainEvent {

        public static EmailDomainMappingCreated of(ExecutionContext ec, EmailDomainMapping m) {
            return new EmailDomainMappingCreated(metadataFor(ec, CREATED, m), m.id(), m.emailDomain());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(mappingId);
        }

        @Override
        public Object data() {
            return new Data(mappingId, emailDomain);
        }

        private record Data(String mappingId, String emailDomain) {
        }
    }

    /// Emitted on update.
    public record EmailDomainMappingUpdated(EventMetadata metadata, String mappingId, String emailDomain)
            implements DomainEvent {

        public static EmailDomainMappingUpdated of(ExecutionContext ec, EmailDomainMapping m) {
            return new EmailDomainMappingUpdated(metadataFor(ec, UPDATED, m), m.id(), m.emailDomain());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(mappingId);
        }

        @Override
        public Object data() {
            return new Data(mappingId, emailDomain);
        }

        private record Data(String mappingId, String emailDomain) {
        }
    }

    /// Emitted on delete.
    public record EmailDomainMappingDeleted(EventMetadata metadata, String mappingId, String emailDomain)
            implements DomainEvent {

        public static EmailDomainMappingDeleted of(ExecutionContext ec, EmailDomainMapping m) {
            return new EmailDomainMappingDeleted(metadataFor(ec, DELETED, m), m.id(), m.emailDomain());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(mappingId);
        }

        @Override
        public Object data() {
            return new Data(mappingId, emailDomain);
        }

        private record Data(String mappingId, String emailDomain) {
        }
    }

    /// Emitted when a domain is re-pointed to a different identity provider;
    /// `m` is the mapping *after* the move, `fromIdentityProviderId` the
    /// provider it left.
    public record EmailDomainMappingProviderChanged(EventMetadata metadata, String mappingId, String emailDomain,
                                                    String fromIdentityProviderId, String toIdentityProviderId)
            implements DomainEvent {

        public static EmailDomainMappingProviderChanged of(ExecutionContext ec, EmailDomainMapping m, String fromIdentityProviderId) {
            return new EmailDomainMappingProviderChanged(metadataFor(ec, PROVIDER_CHANGED, m), m.id(), m.emailDomain(),
                    fromIdentityProviderId, m.identityProviderId());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(mappingId);
        }

        @Override
        public Object data() {
            return new Data(mappingId, emailDomain, fromIdentityProviderId, toIdentityProviderId);
        }

        private record Data(String mappingId, String emailDomain, String fromIdentityProviderId, String toIdentityProviderId) {
        }
    }
}

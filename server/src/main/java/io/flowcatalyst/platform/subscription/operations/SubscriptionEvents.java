package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The subscription aggregate's domain events (spec §8): the type strings,
/// the source, the subject/group builders and one record per event. Every
/// per-aggregate event carries the message group `platform:subscription:{id}`
/// so one subscription's events are delivered in order. Every event has a
/// static `of(…)` factory taking the execution context plus the aggregate,
/// so operations never assemble metadata or payload fields by hand; the
/// `data()` records are the wire payloads, field names verbatim.
public final class SubscriptionEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:subscription:created";
    public static final String UPDATED = "platform:admin:subscription:updated";
    public static final String DELETED = "platform:admin:subscription:deleted";
    public static final String PAUSED = "platform:admin:subscription:paused";
    public static final String RESUMED = "platform:admin:subscription:resumed";
    /// Singular `subscription:synced` — unlike the other sync rollups (spec §8, open question 12).
    public static final String SYNCED = "platform:admin:subscription:synced";

    /// The sync rollup's bare (no-application) message group (spec X-08,
    /// ruled 2026-09-01: per application, see [SubscriptionsSynced#messageGroup()]).
    public static final String SYNC_MESSAGE_GROUP = "platform:subscriptions";

    private SubscriptionEvents() {
    }

    /// `platform.subscription.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String subscriptionId) {
        return EventConventions.buildSubject("platform", "subscription", subscriptionId);
    }

    /// `platform:subscription:{id}` — the message group of every per-aggregate event.
    public static String messageGroupFor(String subscriptionId) {
        return EventConventions.buildMessageGroup("platform", "subscription", subscriptionId);
    }

    /// `platform.subscriptions.{applicationCode}` — the subject of the sync rollup.
    public static String syncSubjectFor(String applicationCode) {
        return EventConventions.buildSubject("platform", "subscriptions", applicationCode);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, Subscription s) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(s.id()));
    }

    /// Emitted on create (and per created row by sync).
    public record SubscriptionCreated(EventMetadata metadata, String subscriptionId, String code, String name)
            implements DomainEvent {

        public static SubscriptionCreated of(ExecutionContext ec, Subscription s) {
            return new SubscriptionCreated(metadataFor(ec, CREATED, s), s.id(), s.code(), s.name());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(subscriptionId);
        }

        @Override
        public Object data() {
            return new Data(subscriptionId, code, name);
        }

        private record Data(String subscriptionId, String code, String name) {
        }
    }

    /// Emitted on update (and per updated row by sync). Carries the name
    /// only — settings changes are not on the wire (spec §8).
    public record SubscriptionUpdated(EventMetadata metadata, String subscriptionId, String name) implements DomainEvent {

        public static SubscriptionUpdated of(ExecutionContext ec, Subscription s) {
            return new SubscriptionUpdated(metadataFor(ec, UPDATED, s), s.id(), s.name());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(subscriptionId);
        }

        @Override
        public Object data() {
            return new Data(subscriptionId, name);
        }

        private record Data(String subscriptionId, String name) {
        }
    }

    /// Emitted on delete (and per removed row by sync).
    public record SubscriptionDeleted(EventMetadata metadata, String subscriptionId, String code) implements DomainEvent {

        public static SubscriptionDeleted of(ExecutionContext ec, Subscription s) {
            return new SubscriptionDeleted(metadataFor(ec, DELETED, s), s.id(), s.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(subscriptionId);
        }

        @Override
        public Object data() {
            return new Data(subscriptionId, code);
        }

        private record Data(String subscriptionId, String code) {
        }
    }

    /// Emitted on every pause, including a no-op one (spec §2).
    public record SubscriptionPaused(EventMetadata metadata, String subscriptionId) implements DomainEvent {

        public static SubscriptionPaused of(ExecutionContext ec, Subscription s) {
            return new SubscriptionPaused(metadataFor(ec, PAUSED, s), s.id());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(subscriptionId);
        }

        @Override
        public Object data() {
            return new Data(subscriptionId);
        }

        private record Data(String subscriptionId) {
        }
    }

    /// Emitted on every resume, including a no-op one (spec §2).
    public record SubscriptionResumed(EventMetadata metadata, String subscriptionId) implements DomainEvent {

        public static SubscriptionResumed of(ExecutionContext ec, Subscription s) {
            return new SubscriptionResumed(metadataFor(ec, RESUMED, s), s.id());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(subscriptionId);
        }

        @Override
        public Object data() {
            return new Data(subscriptionId);
        }

        private record Data(String subscriptionId) {
        }
    }

    /// The rollup emitted by [SyncSubscriptions]: subject
    /// `platform.subscriptions.{applicationCode}`, message group per
    /// application (spec X-08, ruled 2026-09-01 — see [#messageGroup()]).
    public record SubscriptionsSynced(EventMetadata metadata, String applicationCode, int created, int updated,
                                      int deleted, List<String> syncedCodes) implements DomainEvent {

        public SubscriptionsSynced {
            syncedCodes = syncedCodes == null ? List.of() : List.copyOf(syncedCodes);
        }

        public static SubscriptionsSynced of(ExecutionContext ec, String applicationCode, int created, int updated,
                                             int deleted, List<String> syncedCodes) {
            return new SubscriptionsSynced(EventMetadata.of(ec, SYNCED, SOURCE, syncSubjectFor(applicationCode)),
                    applicationCode, created, updated, deleted, syncedCodes);
        }

        /// One FIFO lane per application (spec X-08): `platform:subscriptions:<code>`,
        /// the bare [#SYNC_MESSAGE_GROUP] when there's no application in scope.
        @Override
        public String messageGroup() {
            return applicationCode == null || applicationCode.isBlank()
                    ? SYNC_MESSAGE_GROUP
                    : SYNC_MESSAGE_GROUP + ":" + applicationCode;
        }

        @Override
        public Object data() {
            return new Data(applicationCode, created, updated, deleted, syncedCodes);
        }

        private record Data(String applicationCode, int created, int updated, int deleted, List<String> syncedCodes) {
        }
    }
}

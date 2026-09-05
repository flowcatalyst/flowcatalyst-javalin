package io.flowcatalyst.platform.dispatchpool.operations;

import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The dispatch-pool aggregate's domain events (spec §8): the type strings,
/// the source, the subject/group builders and one record per event. Every
/// event has a static `of(…)` factory that takes the execution context plus
/// the aggregate, so operations never assemble metadata or payload fields
/// by hand; the `data()` records are the wire payloads, field names verbatim.
///
/// Every per-aggregate event carries the message group
/// `platform:dispatchpool:{id}` so one pool's events are delivered in order.
public final class DispatchPoolEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:dispatch-pool:created";
    public static final String UPDATED = "platform:admin:dispatch-pool:updated";
    public static final String ARCHIVED = "platform:admin:dispatch-pool:archived";
    public static final String DELETED = "platform:admin:dispatch-pool:deleted";
    public static final String SUSPENDED = "platform:admin:dispatch-pool:suspended";
    public static final String ACTIVATED = "platform:admin:dispatch-pool:activated";
    public static final String SYNCED = "platform:admin:dispatch-pools:synced";

    /// The sync rollup's bare (no-application) message group (spec X-08,
    /// ruled 2026-09-01: per application, see [DispatchPoolsSynced#messageGroup()]).
    public static final String SYNC_MESSAGE_GROUP = "platform:dispatchpools";

    private DispatchPoolEvents() {
    }

    /// `platform.dispatchpool.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String poolId) {
        return EventConventions.buildSubject("platform", "dispatchpool", poolId);
    }

    /// `platform:dispatchpool:{id}` — the per-pool FIFO ordering key.
    public static String messageGroupFor(String poolId) {
        return EventConventions.buildMessageGroup("platform", "dispatchpool", poolId);
    }

    /// `platform.dispatchpools.{applicationCode}` — the subject of the sync rollup.
    public static String syncSubjectFor(String applicationCode) {
        return EventConventions.buildSubject("platform", "dispatchpools", applicationCode);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, DispatchPool p) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(p.id()));
    }

    /// Emitted on create (and per created row by sync).
    public record DispatchPoolCreated(EventMetadata metadata, String poolId, String code, String name)
            implements DomainEvent {

        public static DispatchPoolCreated of(ExecutionContext ec, DispatchPool p) {
            return new DispatchPoolCreated(metadataFor(ec, CREATED, p), p.id(), p.code(), p.name());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(poolId);
        }

        @Override
        public Object data() {
            return new Data(poolId, code, name);
        }

        private record Data(String poolId, String code, String name) {
        }
    }

    /// Emitted on update (and per updated row by sync). Carries the name
    /// only — rate-limit/concurrency changes are not on the wire (spec §8,
    /// open question 6).
    public record DispatchPoolUpdated(EventMetadata metadata, String poolId, String name) implements DomainEvent {

        public static DispatchPoolUpdated of(ExecutionContext ec, DispatchPool p) {
            return new DispatchPoolUpdated(metadataFor(ec, UPDATED, p), p.id(), p.name());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(poolId);
        }

        @Override
        public Object data() {
            return new Data(poolId, name);
        }

        private record Data(String poolId, String name) {
        }
    }

    /// Emitted when a pool flips to `ARCHIVED` (admin archive, or sync `removeUnlisted`).
    public record DispatchPoolArchived(EventMetadata metadata, String poolId, String code) implements DomainEvent {

        public static DispatchPoolArchived of(ExecutionContext ec, DispatchPool p) {
            return new DispatchPoolArchived(metadataFor(ec, ARCHIVED, p), p.id(), p.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(poolId);
        }

        @Override
        public Object data() {
            return new Data(poolId, code);
        }

        private record Data(String poolId, String code) {
        }
    }

    /// Emitted on hard delete.
    public record DispatchPoolDeleted(EventMetadata metadata, String poolId, String code) implements DomainEvent {

        public static DispatchPoolDeleted of(ExecutionContext ec, DispatchPool p) {
            return new DispatchPoolDeleted(metadataFor(ec, DELETED, p), p.id(), p.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(poolId);
        }

        @Override
        public Object data() {
            return new Data(poolId, code);
        }

        private record Data(String poolId, String code) {
        }
    }

    /// Emitted when a pool flips to `SUSPENDED`.
    public record DispatchPoolSuspended(EventMetadata metadata, String poolId, String code) implements DomainEvent {

        public static DispatchPoolSuspended of(ExecutionContext ec, DispatchPool p) {
            return new DispatchPoolSuspended(metadataFor(ec, SUSPENDED, p), p.id(), p.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(poolId);
        }

        @Override
        public Object data() {
            return new Data(poolId, code);
        }

        private record Data(String poolId, String code) {
        }
    }

    /// Emitted when a pool flips to `ACTIVE`.
    public record DispatchPoolActivated(EventMetadata metadata, String poolId, String code) implements DomainEvent {

        public static DispatchPoolActivated of(ExecutionContext ec, DispatchPool p) {
            return new DispatchPoolActivated(metadataFor(ec, ACTIVATED, p), p.id(), p.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(poolId);
        }

        @Override
        public Object data() {
            return new Data(poolId, code);
        }

        private record Data(String poolId, String code) {
        }
    }

    /// The rollup emitted by [SyncDispatchPools]: subject
    /// `platform.dispatchpools.{applicationCode}`, message group per
    /// application (spec X-08, ruled 2026-09-01 — see [#messageGroup()]).
    /// `deleted` counts the pools *archived* by `removeUnlisted` (spec §7).
    public record DispatchPoolsSynced(EventMetadata metadata, String applicationCode, int created, int updated,
                                      int deleted, List<String> syncedCodes) implements DomainEvent {

        public DispatchPoolsSynced {
            syncedCodes = syncedCodes == null ? List.of() : List.copyOf(syncedCodes);
        }

        public static DispatchPoolsSynced of(ExecutionContext ec, String applicationCode, int created, int updated,
                                             int deleted, List<String> syncedCodes) {
            return new DispatchPoolsSynced(EventMetadata.of(ec, SYNCED, SOURCE, syncSubjectFor(applicationCode)),
                    applicationCode, created, updated, deleted, syncedCodes);
        }

        /// One FIFO lane per application (spec X-08): `platform:dispatchpools:<code>`,
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

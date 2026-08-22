package io.flowcatalyst.platform.process.operations;

import io.flowcatalyst.platform.process.Process;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The process aggregate's domain events (spec §8): the type strings, the
/// source, the subject / message-group builders and one record per event.
/// Every event has a static `of(…)` factory that takes the execution context
/// plus the aggregate, so operations never assemble metadata or payload
/// fields by hand; the `data()` records are the wire payloads, field names
/// verbatim. Per-aggregate events are grouped `platform:process:{id}` so one
/// process's changes are delivered in order.
public final class ProcessEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:process:created";
    public static final String UPDATED = "platform:admin:process:updated";
    public static final String ARCHIVED = "platform:admin:process:archived";
    public static final String DELETED = "platform:admin:process:deleted";
    public static final String SYNCED = "platform:admin:processes:synced";

    /// The sync rollup's message group — one constant, not per application (spec §8, open question 4).
    public static final String SYNC_MESSAGE_GROUP = "platform:processes";

    private ProcessEvents() {
    }

    /// `platform.process.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String processId) {
        return EventConventions.buildSubject("platform", "process", processId);
    }

    /// `platform:process:{id}` — the message group of every per-aggregate event.
    public static String groupFor(String processId) {
        return EventConventions.buildMessageGroup("platform", "process", processId);
    }

    /// `platform.processes.{applicationCode}` — the subject of the sync rollup.
    public static String syncSubjectFor(String applicationCode) {
        return EventConventions.buildSubject("platform", "processes", applicationCode);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, Process p) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(p.id()));
    }

    /// Emitted on create (and per created row by sync).
    public record ProcessCreated(EventMetadata metadata, String processId, String code, String name)
            implements DomainEvent {

        public static ProcessCreated of(ExecutionContext ec, Process p) {
            return new ProcessCreated(metadataFor(ec, CREATED, p), p.id(), p.code(), p.name());
        }

        @Override
        public String messageGroup() {
            return groupFor(processId);
        }

        @Override
        public Object data() {
            return new Data(processId, code, name);
        }

        private record Data(String processId, String code, String name) {
        }
    }

    /// Emitted on update (and per updated row by sync).
    public record ProcessUpdated(EventMetadata metadata, String processId, String name) implements DomainEvent {

        public static ProcessUpdated of(ExecutionContext ec, Process p) {
            return new ProcessUpdated(metadataFor(ec, UPDATED, p), p.id(), p.name());
        }

        @Override
        public String messageGroup() {
            return groupFor(processId);
        }

        @Override
        public Object data() {
            return new Data(processId, name);
        }

        private record Data(String processId, String name) {
        }
    }

    /// Emitted when a process transitions to `ARCHIVED`.
    public record ProcessArchived(EventMetadata metadata, String processId, String code) implements DomainEvent {

        public static ProcessArchived of(ExecutionContext ec, Process p) {
            return new ProcessArchived(metadataFor(ec, ARCHIVED, p), p.id(), p.code());
        }

        @Override
        public String messageGroup() {
            return groupFor(processId);
        }

        @Override
        public Object data() {
            return new Data(processId, code);
        }

        private record Data(String processId, String code) {
        }
    }

    /// Emitted on delete (and per removed row by sync).
    public record ProcessDeleted(EventMetadata metadata, String processId, String code) implements DomainEvent {

        public static ProcessDeleted of(ExecutionContext ec, Process p) {
            return new ProcessDeleted(metadataFor(ec, DELETED, p), p.id(), p.code());
        }

        @Override
        public String messageGroup() {
            return groupFor(processId);
        }

        @Override
        public Object data() {
            return new Data(processId, code);
        }

        private record Data(String processId, String code) {
        }
    }

    /// The rollup emitted by [SyncProcesses]: subject
    /// `platform.processes.{applicationCode}`, message group [#SYNC_MESSAGE_GROUP].
    public record ProcessesSynced(EventMetadata metadata, String applicationCode, int created, int updated,
                                  int deleted, List<String> syncedCodes) implements DomainEvent {

        public ProcessesSynced {
            syncedCodes = syncedCodes == null ? List.of() : List.copyOf(syncedCodes);
        }

        public static ProcessesSynced of(ExecutionContext ec, String applicationCode, int created, int updated,
                                         int deleted, List<String> syncedCodes) {
            return new ProcessesSynced(EventMetadata.of(ec, SYNCED, SOURCE, syncSubjectFor(applicationCode)),
                    applicationCode, created, updated, deleted, syncedCodes);
        }

        @Override
        public String messageGroup() {
            return SYNC_MESSAGE_GROUP;
        }

        @Override
        public Object data() {
            return new Data(applicationCode, created, updated, deleted, syncedCodes);
        }

        private record Data(String applicationCode, int created, int updated, int deleted, List<String> syncedCodes) {
        }
    }
}

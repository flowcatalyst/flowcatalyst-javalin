package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The scheduled-job aggregate's domain events (spec §11): type strings,
/// source, subject builders and one record per event. Every per-aggregate
/// event carries the message group `platform:scheduledjob:{id}` so a job's
/// history is delivered in order. Each record has a static `of(…)` factory
/// taking the execution context plus the aggregate, so operations never
/// assemble metadata; the `data()` records are the wire payloads.
public final class ScheduledJobEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:scheduled-job:created";
    public static final String UPDATED = "platform:admin:scheduled-job:updated";
    public static final String PAUSED = "platform:admin:scheduled-job:paused";
    public static final String RESUMED = "platform:admin:scheduled-job:resumed";
    public static final String ARCHIVED = "platform:admin:scheduled-job:archived";
    public static final String DELETED = "platform:admin:scheduled-job:deleted";
    public static final String FIRED_MANUALLY = "platform:admin:scheduled-job:fired-manually";
    public static final String SYNCED = "platform:admin:scheduledjobs:synced";

    /// The sync rollup's bare (no-application) message group (spec X-08,
    /// ruled 2026-09-01: per application, see [ScheduledJobsSynced#messageGroup()]).
    public static final String SYNC_MESSAGE_GROUP = "platform:scheduledjobs:synced";

    private ScheduledJobEvents() {
    }

    /// `platform.scheduledjob.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String scheduledJobId) {
        return EventConventions.buildSubject("platform", "scheduledjob", scheduledJobId);
    }

    /// `platform:scheduledjob:{id}` — the per-job ordering key.
    public static String messageGroupFor(String scheduledJobId) {
        return EventConventions.buildMessageGroup("platform", "scheduledjob", scheduledJobId);
    }

    /// `platform.scheduledjobs.synced.{applicationCode}` — the subject of the sync rollup.
    public static String syncSubjectFor(String applicationCode) {
        return EventConventions.buildSubject("platform", "scheduledjobs", "synced." + applicationCode);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, ScheduledJob j) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(j.id()));
    }

    /// `{scheduledJobId, code}` — the payload shared by the six lifecycle events.
    private record Data(String scheduledJobId, String code) {
    }

    /// Emitted on create (and per created row by sync).
    public record ScheduledJobCreated(EventMetadata metadata, String scheduledJobId, String code) implements DomainEvent {
        public static ScheduledJobCreated of(ExecutionContext ec, ScheduledJob j) {
            return new ScheduledJobCreated(metadataFor(ec, CREATED, j), j.id(), j.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(scheduledJobId);
        }

        @Override
        public Object data() {
            return new Data(scheduledJobId, code);
        }
    }

    /// Emitted on update (and per changed row by sync).
    public record ScheduledJobUpdated(EventMetadata metadata, String scheduledJobId, String code) implements DomainEvent {
        public static ScheduledJobUpdated of(ExecutionContext ec, ScheduledJob j) {
            return new ScheduledJobUpdated(metadataFor(ec, UPDATED, j), j.id(), j.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(scheduledJobId);
        }

        @Override
        public Object data() {
            return new Data(scheduledJobId, code);
        }
    }

    /// Emitted on pause.
    public record ScheduledJobPaused(EventMetadata metadata, String scheduledJobId, String code) implements DomainEvent {
        public static ScheduledJobPaused of(ExecutionContext ec, ScheduledJob j) {
            return new ScheduledJobPaused(metadataFor(ec, PAUSED, j), j.id(), j.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(scheduledJobId);
        }

        @Override
        public Object data() {
            return new Data(scheduledJobId, code);
        }
    }

    /// Emitted on resume.
    public record ScheduledJobResumed(EventMetadata metadata, String scheduledJobId, String code) implements DomainEvent {
        public static ScheduledJobResumed of(ExecutionContext ec, ScheduledJob j) {
            return new ScheduledJobResumed(metadataFor(ec, RESUMED, j), j.id(), j.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(scheduledJobId);
        }

        @Override
        public Object data() {
            return new Data(scheduledJobId, code);
        }
    }

    /// Emitted on archive (and per archived row by sync).
    public record ScheduledJobArchived(EventMetadata metadata, String scheduledJobId, String code) implements DomainEvent {
        public static ScheduledJobArchived of(ExecutionContext ec, ScheduledJob j) {
            return new ScheduledJobArchived(metadataFor(ec, ARCHIVED, j), j.id(), j.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(scheduledJobId);
        }

        @Override
        public Object data() {
            return new Data(scheduledJobId, code);
        }
    }

    /// Emitted on delete.
    public record ScheduledJobDeleted(EventMetadata metadata, String scheduledJobId, String code) implements DomainEvent {
        public static ScheduledJobDeleted of(ExecutionContext ec, ScheduledJob j) {
            return new ScheduledJobDeleted(metadataFor(ec, DELETED, j), j.id(), j.code());
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(scheduledJobId);
        }

        @Override
        public Object data() {
            return new Data(scheduledJobId, code);
        }
    }

    /// Emitted when a human fires a job outside its schedule; names the
    /// `MANUAL` instance that was inserted (spec §6.1).
    public record ScheduledJobFiredManually(EventMetadata metadata, String scheduledJobId, String code, String instanceId)
            implements DomainEvent {
        public static ScheduledJobFiredManually of(ExecutionContext ec, ScheduledJob j, String instanceId) {
            return new ScheduledJobFiredManually(metadataFor(ec, FIRED_MANUALLY, j), j.id(), j.code(), instanceId);
        }

        @Override
        public String messageGroup() {
            return messageGroupFor(scheduledJobId);
        }

        @Override
        public Object data() {
            return new FiredData(scheduledJobId, code, instanceId);
        }

        private record FiredData(String scheduledJobId, String code, String instanceId) {
        }
    }

    /// The rollup emitted by [SyncScheduledJobs] (spec §8): the affected job
    /// ids split into created / updated / archived (the SDK contract returns
    /// ids, not counts). `clientId` is carried for provenance, not on the wire.
    public record ScheduledJobsSynced(EventMetadata metadata, String applicationCode, String clientId,
                                      List<String> created, List<String> updated, List<String> archived)
            implements DomainEvent {

        public ScheduledJobsSynced {
            created = created == null ? List.of() : List.copyOf(created);
            updated = updated == null ? List.of() : List.copyOf(updated);
            archived = archived == null ? List.of() : List.copyOf(archived);
        }

        public static ScheduledJobsSynced of(ExecutionContext ec, String applicationCode, String clientId,
                                             List<String> created, List<String> updated, List<String> archived) {
            return new ScheduledJobsSynced(EventMetadata.of(ec, SYNCED, SOURCE, syncSubjectFor(applicationCode)),
                    applicationCode, clientId, created, updated, archived);
        }

        /// One FIFO lane per application (spec X-08, ruled 2026-09-01):
        /// `platform:scheduledjobs:<code>`, the bare [#SYNC_MESSAGE_GROUP]
        /// (`platform:scheduledjobs:synced`) when there's no application in scope.
        @Override
        public String messageGroup() {
            return applicationCode == null || applicationCode.isBlank()
                    ? SYNC_MESSAGE_GROUP
                    : "platform:scheduledjobs:" + applicationCode;
        }

        @Override
        public Object data() {
            return new SyncedData(applicationCode, created, updated, archived);
        }

        private record SyncedData(String applicationCode, List<String> created, List<String> updated, List<String> archived) {
        }
    }
}

package io.flowcatalyst.platform.dispatchjob.operations;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.dispatchjob.DispatchJobStatus;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;
import java.util.Objects;

/// The dispatch-job aggregate's domain events (spec §6.1): the type strings,
/// the source, the subject builders and one record per event. Every event
/// has a static `of(…)` factory taking the execution context plus the
/// aggregate, so operations never assemble metadata or payload fields by
/// hand; the `data()` records are the wire payloads, field names verbatim.
///
/// Only the operator's requeue emits events — the infrastructure writers
/// (ingest, scheduler, processing, projector) never do.
public final class DispatchJobEvents {

    public static final String SOURCE = "platform:admin";

    public static final String REQUEUED = "platform:admin:dispatchjob:requeued";
    public static final String BATCH_REQUEUED = "platform:admin:dispatchjobs:requeued";

    /// The source Cancel/Complete emit under — dispatch-seam spec §8's
    /// table, verbatim from Go (`operations/events.go`'s `Source` constant).
    /// Deliberately different from [#SOURCE]: those two verbs are new in
    /// this port and follow the seam spec exactly; Requeue's `platform:admin`
    /// is the already-shipped (and separately flagged, DJ-5) Java behaviour
    /// this unit does not touch.
    public static final String SOURCE_MESSAGING = "platform:messaging";

    public static final String CANCELLED = "platform:messaging:dispatch-job:cancelled";
    public static final String COMPLETED = "platform:messaging:dispatch-job:completed";

    private DispatchJobEvents() {
    }

    /// `platform.dispatchjob.{id}` — the subject of every per-job event.
    public static String subjectFor(String dispatchJobId) {
        return EventConventions.buildSubject("platform", "dispatchjob", dispatchJobId);
    }

    /// `platform:dispatchjob:{id}` — the FIFO group of Cancel/Complete's
    /// per-job event (dispatch-seam spec §8, Go `operations/events.go`'s
    /// `groupFor`). Colon-separated, unlike [#subjectFor]'s dot-separated form.
    static String groupFor(String dispatchJobId) {
        return "platform:dispatchjob:" + dispatchJobId;
    }

    /// `platform.dispatchjobs.{batchId}` — the subject of the requeue rollup:
    /// a batch has no natural key, so the operation mints a 13-char TSID for
    /// it (the audit `entity_id` column is `varchar(17)`) (spec §6.1, open
    /// question 5).
    public static String batchSubjectFor(String batchId) {
        return EventConventions.buildSubject("platform", "dispatchjobs", batchId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, DispatchJob j) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(j.id()));
    }

    /// Emitted per job reset by [RequeueDispatchJobs]; `previousStatus` is
    /// the status the job held before the reset.
    public record DispatchJobRequeued(EventMetadata metadata, String dispatchJobId, String code,
                                      DispatchJobStatus previousStatus, String messageGroup, String clientId)
            implements DomainEvent {

        /// `before` is the job as loaded; the event names what it was.
        public static DispatchJobRequeued of(ExecutionContext ec, DispatchJob before) {
            return new DispatchJobRequeued(metadataFor(ec, REQUEUED, before), before.id(), before.code(),
                    before.status(), before.messageGroup(), before.clientId());
        }

        @Override
        public Object data() {
            return new Data(dispatchJobId, code, previousStatus.name(), messageGroup, clientId);
        }

        private record Data(String dispatchJobId, String code, String previousStatus, String messageGroup,
                            String clientId) {
        }
    }

    /// Emitted by [CancelDispatchJob]: an operator overrode a `FAILED` job to
    /// `CANCELLED` (dispatch-seam spec §8). Single-resource; carries only the id.
    public record DispatchJobCancelled(EventMetadata metadata, String dispatchJobId) implements DomainEvent {

        public static DispatchJobCancelled of(ExecutionContext ec, DispatchJob j) {
            EventMetadata md = EventMetadata.of(ec, CANCELLED, SOURCE_MESSAGING, subjectFor(j.id()))
                    .withMessageGroup(groupFor(j.id()));
            return new DispatchJobCancelled(md, j.id());
        }

        @Override
        public Object data() {
            return new Data(dispatchJobId);
        }

        private record Data(String dispatchJobId) {
        }
    }

    /// Emitted by [CompleteDispatchJob]: an operator overrode a `FAILED` job
    /// to `COMPLETED` (dispatch-seam spec §8) — handled/delivered out of band.
    public record DispatchJobCompleted(EventMetadata metadata, String dispatchJobId) implements DomainEvent {

        public static DispatchJobCompleted of(ExecutionContext ec, DispatchJob j) {
            EventMetadata md = EventMetadata.of(ec, COMPLETED, SOURCE_MESSAGING, subjectFor(j.id()))
                    .withMessageGroup(groupFor(j.id()));
            return new DispatchJobCompleted(md, j.id());
        }

        @Override
        public Object data() {
            return new Data(dispatchJobId);
        }

        private record Data(String dispatchJobId) {
        }
    }

    /// The rollup of one requeue request: the batch id and the ids actually
    /// reset, in request order.
    public record DispatchJobsRequeued(EventMetadata metadata, String batchId, List<String> dispatchJobIds)
            implements DomainEvent {

        public DispatchJobsRequeued {
            Objects.requireNonNull(batchId, "batchId");
            dispatchJobIds = dispatchJobIds == null ? List.of() : List.copyOf(dispatchJobIds);
        }

        public static DispatchJobsRequeued of(ExecutionContext ec, String batchId, List<String> dispatchJobIds) {
            return new DispatchJobsRequeued(
                    EventMetadata.of(ec, BATCH_REQUEUED, SOURCE, batchSubjectFor(batchId)), batchId, dispatchJobIds);
        }

        /// How many rows were reset — the `requeued` the wire reports.
        public int requeued() {
            return dispatchJobIds.size();
        }

        @Override
        public Object data() {
            return new Data(batchId, dispatchJobIds, requeued());
        }

        private record Data(String batchId, List<String> dispatchJobIds, int requeued) {
        }
    }
}

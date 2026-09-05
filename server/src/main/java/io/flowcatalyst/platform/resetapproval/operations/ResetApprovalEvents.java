package io.flowcatalyst.platform.resetapproval.operations;

import io.flowcatalyst.platform.resetapproval.ResetApprovalRequest;
import io.flowcatalyst.platform.resetapproval.ResetApprovalStatus;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The reset-approval aggregate's domain events (spec §8.6, §11.10):
/// source `platform:iam`, subject `platform.reset-approval.<id>`, message
/// group `platform:reset-approval:<id>`. `metadata().principalId()` is the
/// **actor** (the `system` queueing actor, or the deciding admin) — the
/// request's own subject is always named `userId`, never `principalId`
/// (CONVENTIONS §8: an event must never shadow the [DomainEvent] accessor).
public final class ResetApprovalEvents {

    public static final String SOURCE = "platform:iam";

    public static final String QUEUED = "platform:iam:reset-approval:queued";
    public static final String DECIDED = "platform:iam:reset-approval:decided";

    private ResetApprovalEvents() {
    }

    /// `platform.reset-approval.{id}`.
    public static String subjectFor(String requestId) {
        return EventConventions.buildSubject("platform", "reset-approval", requestId);
    }

    /// `platform:reset-approval:{id}`.
    public static String groupFor(String requestId) {
        return EventConventions.buildMessageGroup("platform", "reset-approval", requestId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String requestId) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(requestId)).withMessageGroup(groupFor(requestId));
    }

    /// A fresh `PENDING` request was inserted (spec §8.6): every
    /// client-admin for `clientId` is notified out of band, by
    /// [io.flowcatalyst.platform.resetapproval.ResetApprovalQueue] — not by
    /// a subscriber of this event.
    public record ResetApprovalQueued(EventMetadata metadata, String requestId, String userId, String clientId)
            implements DomainEvent {

        public static ResetApprovalQueued of(ExecutionContext ec, ResetApprovalRequest r) {
            return new ResetApprovalQueued(metadataFor(ec, QUEUED, r.id()), r.id(), r.principalId(), r.clientId());
        }

        @Override
        public Object data() {
            return new Data(requestId, userId, clientId);
        }

        private record Data(String requestId, String userId, String clientId) {
        }
    }

    /// An admin approved or denied the request (spec §8.6, §11.10).
    public record ResetApprovalDecided(EventMetadata metadata, String requestId, String userId, String status, String decidedBy)
            implements DomainEvent {

        public static ResetApprovalDecided of(ExecutionContext ec, ResetApprovalRequest r, ResetApprovalStatus status, String decidedBy) {
            return new ResetApprovalDecided(metadataFor(ec, DECIDED, r.id()), r.id(), r.principalId(), status.name(), decidedBy);
        }

        @Override
        public Object data() {
            return new Data(requestId, userId, status, decidedBy);
        }

        private record Data(String requestId, String userId, String status, String decidedBy) {
        }
    }
}

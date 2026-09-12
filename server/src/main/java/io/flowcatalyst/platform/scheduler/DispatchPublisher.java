package io.flowcatalyst.platform.scheduler;

import java.util.List;
import java.util.Objects;

/// Hands a claimed batch of dispatch jobs to the message queue the router
/// consumes from (dispatch-seam spec §3, step 5). `PendingJobPoller` calls
/// this exactly once per poll tick, AFTER the claim transaction has
/// committed — never inside it (spec §3: a commit failure after publishing
/// would re-claim an already-published job; a publish failure's revert would
/// no-op if the `QUEUED` status it guards on had not committed yet).
///
/// **Publishing is no longer all-or-nothing (ruling O2,
/// `docs/go-mirror/2026-09-12-dispatch-rulings.md`) — superseding this
/// paragraph's original text, kept below for the record.** SQS caps a
/// `SendMessageBatch` call at 10 entries while `PendingJobPoller` claims up
/// to 100, so [#publish] chunks internally; a chunk failure is SQS's normal
/// operating mode, not an exceptional one, and jobs already accepted by the
/// broker are legitimately [io.flowcatalyst.platform.dispatchjob.DispatchJobStatus#QUEUED]
/// — reverting them too would republish them and create duplicates. [#publish]
/// now either returns having published every message in `batch`, or throws
/// [PublishException] carrying exactly the job ids that were NOT published;
/// the caller reverts only those, QUEUED → PENDING. [NoopPublisher] (never
/// fails) and [PostgresQueuePublisher] (one statement, one Postgres
/// transaction) are still effectively all-or-nothing in practice — a
/// [PostgresQueuePublisher] failure reports its whole batch as unpublished —
/// but the contract itself no longer requires it, and an implementation MUST
/// NOT claim a job unpublished that the broker actually accepted, or vice
/// versa: [PendingJobPoller] trusts [PublishException#unpublishedJobIds()]
/// exactly.
///
/// ~~Original text (all-or-nothing, superseded above):~~ ~~Publishing is
/// all-or-nothing from the caller's point of view: `#publish` either returns
/// having published every message in `batch`, or throws `PublishException`
/// and the caller reverts the WHOLE batch `QUEUED` →
/// `DispatchJobStatus#PENDING` (spec §3, step 6).~~ A re-published duplicate
/// after a revert is harmless either way: the FIFO queue's dedup id (see
/// below) only ever prevents a broker-level duplicate of the SAME publish
/// attempt, never the processing endpoint's own terminal-status check, which
/// no-ops a job already delivered.
///
/// **MUST NOT set any queue-native deduplication id AS A DEDUPLICATION
/// AUTHORITY (ledger R-18, clarified — not discarded — by ruling R2).** R-18's
/// original text ("MUST NOT set any queue-native deduplication id") predates
/// FIFO backends, which have no such option: SQS FIFO REQUIRES a
/// `MessageDeduplicationId` on every send. R2 settles the apparent conflict:
/// the platform's own `status`/`scheduled_for` machinery remains the ONLY
/// deduplication authority this codebase relies on — a publisher MUST NOT
/// depend on the broker to deduplicate anything. Where a backend structurally
/// requires a dedup id anyway, the id supplied MUST be unique to that one
/// publish attempt (job id + a value never reused across attempts), so the
/// broker can never actually deduplicate a genuine re-publish (e.g.
/// `StaleQueuedJobPoller` reclaiming a stranded `QUEUED` row) inside its own
/// dedup window — see [SqsDispatchPublisher] for where this is load-bearing.
public interface DispatchPublisher {

    void publish(List<PublishedMessage> batch) throws PublishException;

    /// The batch failed to publish, in whole or in part (ruling O2). Carries
    /// the underlying cause (when there is a single one to attach; `null` is
    /// permitted — see [#cause()]) and — the part [PendingJobPoller] actually
    /// acts on — exactly the ids of the jobs in the batch that were NOT
    /// published, so the caller can revert precisely those and leave every
    /// successfully published job `QUEUED`. Never thrown for an empty batch
    /// (callers should not call [#publish] with one, but an implementation
    /// that receives one publishes nothing and succeeds trivially).
    final class PublishException extends Exception {
        private final List<String> unpublishedJobIds;

        /// @param unpublishedJobIds the job ids from the failed [#publish]
        ///                          call that were NOT published — never
        ///                          `null`, may be empty only if the
        ///                          implementation has nothing more specific
        ///                          to report than "this call failed"
        ///                          (defensively copied)
        public PublishException(String message, Throwable cause, List<String> unpublishedJobIds) {
            super(message, cause);
            this.unpublishedJobIds = List.copyOf(Objects.requireNonNull(unpublishedJobIds, "unpublishedJobIds"));
        }

        public List<String> unpublishedJobIds() {
            return unpublishedJobIds;
        }
    }
}

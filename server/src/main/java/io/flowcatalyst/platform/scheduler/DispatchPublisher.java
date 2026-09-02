package io.flowcatalyst.platform.scheduler;

import java.util.List;

/// Hands a claimed batch of dispatch jobs to the message queue the router
/// consumes from (dispatch-seam spec §3, step 5). `PendingJobPoller` calls
/// this exactly once per poll tick, AFTER the claim transaction has
/// committed — never inside it (spec §3: a commit failure after publishing
/// would re-claim an already-published job; a publish failure's revert would
/// no-op if the `QUEUED` status it guards on had not committed yet).
///
/// Publishing is all-or-nothing from the caller's point of view: [#publish]
/// either returns having published every message in `batch`, or throws
/// [PublishException] and the caller reverts the WHOLE batch `QUEUED` →
/// [io.flowcatalyst.platform.dispatchjob.DispatchJobStatus#PENDING] (spec §3,
/// step 6) — a checked failure outcome rather than a `boolean` return, so a
/// partial failure (some rows inserted, then a connection error) still
/// surfaces as "the batch failed" with a cause attached, never as a
/// silently-swallowed `false`. A re-published duplicate after such a revert
/// is harmless: the FIFO queue content-dedups, and the processing endpoint's
/// terminal-status check no-ops a job already delivered.
///
/// MUST NOT set any queue-native deduplication id (ledger R-18) — the
/// built-in Postgres broker has no such column, but the rule is binding on
/// any future backend this interface grows: dedup is the platform's own
/// `status`/`scheduled_for` machinery, never the broker's.
public interface DispatchPublisher {

    void publish(List<PublishedMessage> batch) throws PublishException;

    /// The batch failed to publish. Carries the underlying cause; never
    /// thrown for an empty batch (callers should not call [#publish] with
    /// one, but an implementation that receives one publishes nothing and
    /// succeeds trivially).
    final class PublishException extends Exception {
        public PublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

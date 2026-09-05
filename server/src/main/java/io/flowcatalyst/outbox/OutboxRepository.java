package io.flowcatalyst.outbox;

import java.time.Duration;
import java.util.List;

/// The outbox's persistence seam (spec §3). [PostgresOutboxRepository] is
/// the only implementation — Mongo/SQLite stay on the backlog by owner
/// ruling (spec §8 Q2).
public interface OutboxRepository {

    /// Creates `outbox_messages` and its two partial indexes if they do not
    /// already exist (spec §2). Idempotent — safe to call on every start.
    void initSchema();

    /// Claims up to `n` `PENDING` rows in one transaction: `SELECT ... FOR
    /// UPDATE SKIP LOCKED` ordered `(message_group, created_at)`, then marks
    /// the survivors `IN_PROGRESS` before returning them (spec §3).
    List<OutboxItem> claimPending(int n);

    /// Deletes the rows outright: a delivered item leaves no trace (spec §2, §3).
    void markSuccess(List<String> ids);

    /// `status = requeue ? PENDING : status`, records `error_message`, and
    /// bumps `retry_count` by one — regardless of `requeue` (spec §3).
    void markFailed(List<String> ids, OutboxStatus status, String message, boolean requeue);

    /// Returns claimed-but-never-attempted rows to `PENDING` untouched — only
    /// a row still `IN_PROGRESS` is affected, and `retry_count` /
    /// `error_message` are not touched (spec §3): a group found inactive at
    /// claim time did nothing wrong.
    void release(List<String> ids);

    /// The admin "unblock": `PENDING`, `retry_count = 0`, `error_message`
    /// cleared (spec §3).
    void requeue(List<String> ids);

    /// Returns `IN_PROGRESS` rows whose `updated_at` is older than
    /// `olderThan` to `PENDING` (crash recovery, spec §3).
    ///
    /// @return the number of rows recovered
    int recoverStuck(Duration olderThan);

    /// A cheap connectivity probe.
    boolean healthy();
}

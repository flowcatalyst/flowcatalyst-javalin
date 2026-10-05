-- msg_dispatch_queue: the waiting dispatch jobs, in a small unpartitioned table
-- with an ordinary index (dispatch-queue spec, step 2 of 3).
--
-- Invariant, kept by DispatchJobLifecycle with explicit writes in the same SQL
-- statement as the job's own status change (no triggers): this table has exactly
-- one row for every msg_dispatch_jobs row whose status = 'PENDING', and none for
-- any other job. The row mirrors the job's current values; `version` is the
-- job's updated_at when the row was written.
--
-- Nothing reads this table to dispatch yet; the scheduler's claim moves onto it
-- in step 3 (`claimed_at` is that step's).
--
-- The DDL is identical in the Go (goose) and Rust migrations: the three
-- implementations share one physical database, and whichever deploys first
-- applies it. Additive only: a new table Go and Rust ignore until they carry the
-- same migration. No foreign key: the parent is partitioned and its partitions
-- are dropped. Idempotent throughout.
--
-- Down / rollback: DROP TABLE msg_dispatch_queue.

CREATE TABLE IF NOT EXISTS msg_dispatch_queue (
    job_id           VARCHAR(13)  PRIMARY KEY,
    job_created_at   TIMESTAMPTZ  NOT NULL,
    message_group    VARCHAR(200),
    sequence         INTEGER      NOT NULL,
    scheduled_for    TIMESTAMPTZ,
    subscription_id  VARCHAR(17),
    dispatch_pool_id VARCHAR(17),
    client_id        VARCHAR(17),
    mode             VARCHAR(30)  NOT NULL,
    queue            VARCHAR(255),
    version          TIMESTAMPTZ  NOT NULL,
    claimed_at       TIMESTAMPTZ,
    enqueued_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dispatch_queue_order
    ON msg_dispatch_queue (message_group NULLS LAST, sequence, job_created_at, job_id);

INSERT INTO msg_dispatch_queue
    (job_id, job_created_at, message_group, sequence, scheduled_for, subscription_id,
     dispatch_pool_id, client_id, mode, queue, version)
SELECT id, created_at, message_group, sequence, scheduled_for, subscription_id,
       dispatch_pool_id, client_id, mode, queue, updated_at
  FROM msg_dispatch_jobs
 WHERE status = 'PENDING'
ON CONFLICT (job_id) DO NOTHING;

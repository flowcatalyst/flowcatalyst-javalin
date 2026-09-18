-- Adopted from flowcatalyst-go internal/migrate/sql/054_dispatch_job_queue.sql
-- A dispatch job carries its own queue priority (owner ruling 2026-09-18,
-- docs/spec/dispatch-job-priority.md).
--
-- Priority used to live only on msg_subscriptions.queue, resolved at
-- publish time from the job's subscription_id. That meant a directly
-- created job (POST /api/dispatch-jobs, /api/dispatch-jobs/batch — no
-- subscription) could never ask for HIGH_PRIORITY, and an already-created
-- job's priority drifted if its subscription was later edited or deleted.
--
-- msg_dispatch_jobs.queue is the job's own claim: nullable and absent is
-- the legacy state, not an error, and same width/shape as
-- msg_subscriptions.queue (V4) so the two columns hold the same
-- DEFAULT | HIGH_PRIORITY | legacy-text values. Go adds the identical
-- column as its own migration (054_dispatch_job_queue.sql) — both
-- platforms share one database, so whichever deploys first wins and the
-- other's ADD COLUMN IF NOT EXISTS is a no-op.
--
-- Resolution order at publish (DispatchDestinationResolver): the job's own
-- queue when it names a recognised value, else the subscription's (today's
-- lookup), else DEFAULT. See QueuePriority#forJob.

-- +goose Up
ALTER TABLE msg_dispatch_jobs
    ADD COLUMN IF NOT EXISTS queue VARCHAR(255);

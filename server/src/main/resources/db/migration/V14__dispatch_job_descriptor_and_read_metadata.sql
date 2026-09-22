-- Adopted from flowcatalyst-go internal/migrate/sql/057_dispatch_job_descriptor_and_read_metadata.sql,
-- statement for statement.
--
-- A dispatch job carries a human-readable descriptor, and the read projection
-- carries the job's metadata (owner, 2026-09-22: the dispatch-jobs grid must
-- say WHAT a job is, not just its code and target URL).
--
-- descriptor: for a job the event fan-out raises, the raising subscription's
-- name — "Notify Value of user logins" reads on a grid where
-- "value:iam:user:logged-in" does not. A directly created job
-- (POST /api/dispatch-jobs(/batch)) may supply its own. Nullable; absent is
-- the legacy state. Both tables, since the grid reads the projection.
--
-- msg_dispatch_jobs_read.metadata: the job's key/value tags, projected from
-- the write row so the grid can show them (the projection deliberately
-- omitted payload/metadata; metadata is small, payload stays out). The
-- fan-out now copies the raising EVENT's context_data onto the job's
-- metadata, so a job shows the same "additional data" its event does.
ALTER TABLE msg_dispatch_jobs
    ADD COLUMN IF NOT EXISTS descriptor VARCHAR(255);

ALTER TABLE msg_dispatch_jobs_read
    ADD COLUMN IF NOT EXISTS descriptor VARCHAR(255),
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '[]'::jsonb;

-- msg_dispatch_job_attempts.request_info: what the platform SENT on this
-- attempt — which service account signed it, whether a signature and a
-- bearer were attached, the signing timestamp, the header names — or why
-- it went out unsigned. Never a secret. Until now an attempt recorded only
-- the subscriber's answer, and on a failure not even its body, so a 401
-- was undiagnosable from the platform's own records.
ALTER TABLE msg_dispatch_job_attempts
    ADD COLUMN IF NOT EXISTS request_info JSONB;

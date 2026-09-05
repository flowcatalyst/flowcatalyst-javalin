-- Adopted from flowcatalyst-go internal/migrate/sql/048_dispatch_mode_default.sql
-- Changes what an unspecified dispatch mode defaults to: NEXT_ON_ERROR instead
-- of IMMEDIATE, so an omitted mode keeps a message group in sequence rather
-- than silently dropping ordering. Column defaults only; existing rows are
-- unaffected.

ALTER TABLE msg_subscriptions ALTER COLUMN mode SET DEFAULT 'NEXT_ON_ERROR';
ALTER TABLE msg_dispatch_jobs ALTER COLUMN mode SET DEFAULT 'NEXT_ON_ERROR';

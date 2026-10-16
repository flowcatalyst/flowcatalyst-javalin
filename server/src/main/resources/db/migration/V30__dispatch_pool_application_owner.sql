-- A dispatch pool created by an application's SDK sync records the application
-- that owns it (Go platform-bug-fixes F19, migration 078). Pools are global rows
-- matched by code; the built-in platform:application-service role now carries
-- platform:messaging:dispatch-pool:sync (V31), so without an owner any
-- application's service account could overwrite another application's pool, or a
-- platform pool, by sending its code. The sync lets an application-scoped caller
-- change only pools whose application_id is its own application.
--
-- NULL = no owner (every pool that exists today, and pools created in the admin
-- UI/API): only an all-applications caller may sync those, and doing so hands
-- them to the application being synced. No foreign key. Idempotent and the same
-- statements as Go's 078, so running both migrators on one database is harmless.
ALTER TABLE msg_dispatch_pools ADD COLUMN IF NOT EXISTS application_id VARCHAR(17);
CREATE INDEX IF NOT EXISTS idx_msg_dispatch_pools_application_id ON msg_dispatch_pools (application_id);

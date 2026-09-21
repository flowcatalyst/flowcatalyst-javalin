-- Adopted from flowcatalyst-go internal/migrate/sql/056_connection_application_scope.sql
-- Connections gain the same optional application link and authorship
-- ("source") that subscriptions already carry (msg_subscriptions.application_code
-- / .source), so a later code-first sync can tell a code/API-authored
-- connection apart from a UI-authored one and refuse to touch the latter —
-- exactly the rule SyncSubscriptions already enforces for subscriptions.
--
-- application_code matches msg_subscriptions.application_code's type exactly
-- (VARCHAR(100), nullable, no FK — app_applications.code is not
-- unique-indexed as a foreign key target in this schema; every other
-- application_code column in this codebase is a bare column for the same
-- reason).
ALTER TABLE msg_connections ADD COLUMN IF NOT EXISTS application_code VARCHAR(100);

-- source: existing rows predate the concept and were all created through the
-- UI/API create path (there is no other way a msg_connections row comes to
-- exist), so 'UI' is the correct backfill, not just a convenient default.
ALTER TABLE msg_connections ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'UI';

-- connection.Source — internal/platform/connection/entity.go (ConnectionSource
-- in Java). Same guarded CHECK style as the rest of the X-06 enum constraints
-- (V6): matches the Go const block exactly, and idempotent via the
-- pg_constraint existence check (no ADD CONSTRAINT IF NOT EXISTS in
-- PostgreSQL).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_msg_connections_source'
    ) THEN
        ALTER TABLE msg_connections
            ADD CONSTRAINT chk_msg_connections_source
            CHECK (source IN ('CODE', 'API', 'UI'));
    END IF;
END $$;

-- Code uniqueness moves from per-CLIENT to per-APPLICATION-and-client for
-- both msg_connections and msg_subscriptions: the key becomes
-- (application_code, client_id, code), where "no application" (a *shared*
-- connection/subscription — not to be confused with "no client", which this
-- codebase calls global) and "no client" are each real values in that key,
-- not wildcards. The existing idx_msg_connections_code_client /
-- idx_msg_subscriptions_code_client indexes (V1__baseline.sql) are plain
-- UNIQUE (code, client_id): Postgres treats NULLs as distinct in a unique
-- index, so two rows with the same code and both a NULL client_id were never
-- actually rejected by them — a latent gap this migration closes at the same
-- time it adds the application dimension.
--
-- Pre-checks: FAIL LOUDLY before touching the indexes if any table already
-- has rows that would collide under the new key, naming the offending
-- groups so an operator can resolve them by hand. A fresh/normal database
-- has none — every row here was already unique under the OLD (code,
-- client_id) index except for the same-code-both-NULL-client_id gap
-- described above, which is exactly the case the new index is meant to
-- catch. Grouping matches the index expression exactly (COALESCE(...,'')
-- for both nullable parts) so nothing here can pass the check and then fail
-- the CREATE UNIQUE INDEX below with a raw constraint-violation error.
DO $$
DECLARE
    dupes TEXT;
BEGIN
    SELECT string_agg(
               format('(application_code=%L, client_id=%L, code=%L, count=%s)',
                      NULLIF(application_code, ''), NULLIF(client_id, ''), code, cnt),
               ', ')
      INTO dupes
      FROM (
          SELECT COALESCE(application_code, '') AS application_code,
                 COALESCE(client_id, '') AS client_id,
                 code,
                 COUNT(*) AS cnt
            FROM msg_connections
           GROUP BY 1, 2, 3
          HAVING COUNT(*) > 1
      ) d;

    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'msg_connections has rows that collide under the new (application_code, client_id, code) uniqueness key — resolve these before re-running this migration: %', dupes;
    END IF;
END $$;

DO $$
DECLARE
    dupes TEXT;
BEGIN
    SELECT string_agg(
               format('(application_code=%L, client_id=%L, code=%L, count=%s)',
                      NULLIF(application_code, ''), NULLIF(client_id, ''), code, cnt),
               ', ')
      INTO dupes
      FROM (
          SELECT COALESCE(application_code, '') AS application_code,
                 COALESCE(client_id, '') AS client_id,
                 code,
                 COUNT(*) AS cnt
            FROM msg_subscriptions
           GROUP BY 1, 2, 3
          HAVING COUNT(*) > 1
      ) d;

    IF dupes IS NOT NULL THEN
        RAISE EXCEPTION 'msg_subscriptions has rows that collide under the new (application_code, client_id, code) uniqueness key — resolve these before re-running this migration: %', dupes;
    END IF;
END $$;

-- Undoes what V1__baseline.sql does on a fresh database (V1 itself is not
-- edited — it carries the baseline Flyway checksum): a Flyway baseline run
-- against a fresh database after Go's 056 would otherwise put the old
-- per-client indexes back and silently reinstate per-client uniqueness.
DROP INDEX IF EXISTS idx_msg_connections_code_client;
DROP INDEX IF EXISTS idx_msg_subscriptions_code_client;

-- Expression-based (not a NULLS NOT DISTINCT unique index) so this doesn't
-- depend on the deployed Postgres version supporting that syntax (PG 15+):
-- COALESCE(..., '') folds every "no application"/"no client" row onto the
-- same key value, which the b-tree unique index then treats as one bucket.
CREATE UNIQUE INDEX IF NOT EXISTS uq_msg_connections_app_client_code
    ON msg_connections (COALESCE(application_code, ''), COALESCE(client_id, ''), code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_msg_subscriptions_app_client_code
    ON msg_subscriptions (COALESCE(application_code, ''), COALESCE(client_id, ''), code);
